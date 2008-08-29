/*
 * Copyright (C) 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.photostream;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.content.Context;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.TranslateAnimation;
import android.view.animation.Animation;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;
import android.widget.ViewAnimator;

import java.util.Random;

/**
 * Activity used to display a Flickr user's photostream. This activity shows a fixed
 * number of photos at a time. The activity is invoked either by LoginActivity, when
 * the application is launched normally, or by a Home shortcut, or by an Intent with
 * the view action and a flickr://photos/nsid URI.
 */
public class PhotostreamActivity extends Activity implements
        View.OnClickListener, Animation.AnimationListener {

    private static final String EXTRA_USER = "com.google.android.photostream.extra_user";

    private static final String STATE_USER = "com.google.android.photostream.state_user";
    private static final String STATE_PAGE = "com.google.android.photostream.state_page";

    private static final int PHOTOS_COUNT_PER_PAGE = 6;

    private Flickr.User mUser;
    private int mCurrentPage = 1;

    private ViewAnimator mSwitcher;
    private View mMenuNext;
    private View mMenuBack;
    private View mMenuSeparator;
    private GridLayout mGrid;

    private LayoutAnimationController mNextAnimation;
    private LayoutAnimationController mBackAnimation;

    private UserTask<?, ?, ?> mTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Try to find a user name in the saved instance state or the intent
        // that launched the activity. If no valid user NSID can be found, we
        // just close the activity.
        if (!initialize(savedInstanceState)) {
            finish();
            return;
        }

        setContentView(R.layout.screen_photostream);
        setupViews();

        loadPhotos();
    }

    /**
     * Starts the PhotostreamActivity for the specified user.
     *
     * @param context The application's environment.
     * @param user The user whose photos to display with a PhotostreamActivity.
     */
    static void show(Context context, Flickr.User user) {
        final Intent intent = new Intent(context, PhotostreamActivity.class);
        intent.putExtra(EXTRA_USER, user);
        context.startActivity(intent);
    }

    /**
     * Restores a previously saved state or, if missing, finds the user's NSID
     * from the intent used to start the activity.
     *
     * @param savedInstanceState The saved state, if any.
     *
     * @return true if a {@link com.google.android.photostream.Flickr.User} was
     *         found either in the saved state or the intent.
     */
    private boolean initialize(Bundle savedInstanceState) {
        Flickr.User user;
        if (savedInstanceState != null) {
            user = savedInstanceState.getParcelable(STATE_USER);
            mCurrentPage = savedInstanceState.getInt(STATE_PAGE);
        } else {
            user = getUser();
            if (user == null) {
                return false;
            }
        }
        mUser = user;
        return mUser != null;
    }

    /**
     * Creates a {@link com.google.android.photostream.Flickr.User} instance
     * from the intent used to start this activity. The user can either be
     * a parceled instance, or created from a flickr://photos/nsid URI.
     *
     * @return The user whose photos will be displayed, or null if no
     *         user was found.
     */
    private Flickr.User getUser() {
        final Intent intent = getIntent();
        final Bundle extras = intent.getExtras();

        Flickr.User user = null;

        if (extras != null) {
            user = extras.getParcelable(EXTRA_USER);
        }

        if (user == null) {
            final Uri uri = intent.getData();
            if (uri != null) {
                if (Flickr.URI_SCHEME.equals(uri.getScheme()) &&
                        Flickr.URI_PHOTOS_AUTHORITY.equals(uri.getAuthority())) {
                    user = Flickr.User.fromId(uri.getLastPathSegment());
                }
            }
        }

        return user;
    }

    private void setupViews() {
        mNextAnimation = AnimationUtils.loadLayoutAnimation(this, R.anim.layout_slide_next);
        mBackAnimation = AnimationUtils.loadLayoutAnimation(this, R.anim.layout_slide_back);

        mSwitcher = (ViewAnimator) findViewById(R.id.switcher_menu);
        mMenuNext = findViewById(R.id.menu_next);
        mMenuBack = findViewById(R.id.menu_back);
        mMenuSeparator = findViewById(R.id.menu_separator);
        mGrid = (GridLayout) findViewById(R.id.grid_photos);

        mMenuNext.setOnClickListener(this);
        mMenuBack.setOnClickListener(this);
        mMenuBack.setVisibility(View.GONE);
        mMenuSeparator.setVisibility(View.GONE);
        mGrid.setClipToPadding(false);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putParcelable(STATE_USER, mUser);
        outState.putInt(STATE_PAGE, mCurrentPage);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mTask != null && mTask.getStatus() == UserTask.Status.RUNNING) {
            mTask.cancel(true);
        }
    }

    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.menu_next:
                onNext();
                break;
            case R.id.menu_back:
                onBack();
                break;
            default:
                onShowPhoto((Flickr.Photo) v.getTag());
                break;
        }
    }

    private void prepareMenu(Flickr.PhotoList list) {
        if (list == null) return;

        final boolean backVisible = mCurrentPage > 1;
        final boolean nextVisible = mCurrentPage < list.getPageCount();

        mMenuBack.setVisibility(backVisible ? View.VISIBLE : View.GONE);
        mMenuNext.setVisibility(nextVisible ? View.VISIBLE : View.GONE);

        mMenuSeparator.setVisibility(backVisible && nextVisible ? View.VISIBLE : View.GONE);
    }

    private void loadPhotos() {
        mTask = new GetPhotoListTask().execute(mCurrentPage);
    }

    private void showPhotos(Flickr.PhotoList photos) {
        mTask = new LoadPhotosTask().execute(photos);
    }

    private void onShowPhoto(Flickr.Photo photo) {
        ViewPhotoActivity.show(this, photo);
    }

    private void onNext() {
        mCurrentPage++;
        animateAndLoadPhotos(mNextAnimation);
    }

    private void onBack() {
        mCurrentPage--;
        animateAndLoadPhotos(mBackAnimation);
    }

    private void animateAndLoadPhotos(LayoutAnimationController animation) {
        mSwitcher.showNext();
        mGrid.setLayoutAnimationListener(this);
        mGrid.setLayoutAnimation(animation);
        mGrid.invalidate();
    }

    public void onAnimationEnd(Animation animation) {
        mGrid.setLayoutAnimationListener(null);
        mGrid.setLayoutAnimation(null);
        mGrid.removeAllViews();
        loadPhotos();
    }

    public void onAnimationStart(Animation animation) {
    }

    public void onAnimationRepeat(Animation animation) {
    }

    private static Animation createAnimationForChild(int childIndex) {
        boolean firstColumn = (childIndex & 0x1) == 0;

        Animation translate = new TranslateAnimation(
                Animation.RELATIVE_TO_SELF, firstColumn ? -1.1f : 1.1f,
                Animation.RELATIVE_TO_SELF, 0.0f,
                Animation.RELATIVE_TO_SELF, 0.0f,
                Animation.RELATIVE_TO_SELF, 0.0f);

        translate.setInterpolator(new AccelerateDecelerateInterpolator());
        translate.setFillAfter(false);
        translate.setDuration(300);

        return translate;
    }

    /**
     * Background task used to load each individual photo. The task loads each photo
     * in order and publishes each loaded Bitmap as a progress unit. The tasks ends
     * by hiding the progress bar and showing the menu.
     */
    private class LoadPhotosTask extends UserTask<Flickr.PhotoList, LoadedPhoto, Flickr.PhotoList> {
        private final LayoutInflater mInflater;
        private final Random mRandom;

        private LoadPhotosTask() {
            mInflater = LayoutInflater.from(PhotostreamActivity.this);
            mRandom = new Random();
        }

        public Flickr.PhotoList doInBackground(Flickr.PhotoList... params) {
            final Flickr.PhotoList list = params[0];
            final int count = list.getCount();

            for (int i = 0; i < count; i++) {
                if (isCancelled()) continue;

                final Flickr.Photo photo = list.get(i);
                Bitmap bitmap = photo.loadPhotoBitmap(Flickr.PhotoSize.THUMBNAIL);
                if (!isCancelled()) {
                    if (bitmap == null) {
                        final boolean portrait = mRandom.nextFloat() >= 0.5f;
                        bitmap = BitmapFactory.decodeResource(getResources(), portrait ?
                            R.drawable.not_found_small_1 : R.drawable.not_found_small_2);
                    }
                    publishProgress(new LoadedPhoto(ImageUtilities.rotateAndFrame(bitmap), photo));
                    bitmap.recycle();
                }
            }

            return list;
        }

        /**
         * Whenever a photo's Bitmap is loaded from the background thread, it is
         * displayed in this method by adding a new ImageView in the photos grid.
         * Each ImageView's tag contains the {@link com.google.android.photostream.Flickr.Photo}
         * it was loaded from.
         *
         * @param value The photo and its bitmap.
         */
        @Override
        public void processProgress(LoadedPhoto... value) {
            ImageView image = (ImageView) mInflater.inflate(R.layout.grid_item_photo, mGrid, false);
            image.setImageBitmap(value[0].mBitmap);
            image.startAnimation(createAnimationForChild(mGrid.getChildCount()));
            image.setTag(value[0].mPhoto);
            image.setOnClickListener(PhotostreamActivity.this);
            mGrid.addView(image);
        }

        @Override
        public void end(Flickr.PhotoList result) {
            prepareMenu(result);
            mSwitcher.showNext();
            mTask = null;            
        }
    }

    /**
     * Background task used to load the list of photos. The tasks queries Flickr for the
     * list of photos to display and ends by starting the LoadPhotosTask.
     */
    private class GetPhotoListTask extends UserTask<Integer, Void, Flickr.PhotoList> {
        public Flickr.PhotoList doInBackground(Integer... params) {
            return Flickr.get().getPublicPhotos(mUser, PHOTOS_COUNT_PER_PAGE, params[0]);
        }

        @Override
        public void end(Flickr.PhotoList photoList) {
            showPhotos(photoList);
            mTask = null;
        }
    }

    /**
     * A LoadedPhoto contains the Flickr photo and the Bitmap loaded for that photo.
     */
    private static class LoadedPhoto {
        Bitmap mBitmap;
        Flickr.Photo mPhoto;

        LoadedPhoto(Bitmap bitmap, Flickr.Photo photo) {
            mBitmap = bitmap;
            mPhoto = photo;
        }
    }
}
