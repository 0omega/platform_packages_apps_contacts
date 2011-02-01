/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.contacts;

import com.android.contacts.model.AccountTypeManager;
import com.google.android.collect.Lists;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Handler.Callback;
import android.os.HandlerThread;
import android.os.Message;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Contacts.Photo;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.Directory;
import android.util.Log;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Asynchronously loads contact photos and maintains a cache of photos.
 */
public abstract class ContactPhotoManager {

    static final String TAG = "ContactPhotoManager";

    public static final String CONTACT_PHOTO_SERVICE = "contactPhotos";

    /**
     * The resource ID of the image to be used when the photo is unavailable or being
     * loaded.
     */
    protected final int mDefaultResourceId = R.drawable.ic_contact_picture;

    /**
     * Requests the singleton instance of {@link AccountTypeManager} with data bound from
     * the available authenticators. This method can safely be called from the UI thread.
     */
    public static ContactPhotoManager getInstance(Context context) {
        ContactPhotoManager service =
                (ContactPhotoManager) context.getSystemService(CONTACT_PHOTO_SERVICE);
        if (service == null) {
            service = createContactPhotoManager(context);
            Log.e(TAG, "No contact photo service in context: " + context);
        }
        return service;
    }

    public static synchronized ContactPhotoManager createContactPhotoManager(Context context) {
        return new ContactPhotoManagerImpl(context);
    }

    /**
     * Load photo into the supplied image view.  If the photo is already cached,
     * it is displayed immediately.  Otherwise a request is sent to load the photo
     * from the database.
     */
    public abstract void loadPhoto(ImageView view, long photoId);

    /**
     * Load photo into the supplied image view.  If the photo is already cached,
     * it is displayed immediately.  Otherwise a request is sent to load the photo
     * from the location specified by the URI.
     */
    public abstract void loadPhoto(ImageView view, Uri photoUri);

    /**
     * Temporarily stops loading photos from the database.
     */
    public abstract void pause();

    /**
     * Resumes loading photos from the database.
     */
    public abstract void resume();

    /**
     * Marks all cached photos for reloading.  We can continue using cache but should
     * also make sure the photos haven't changed in the background and notify the views
     * if so.
     */
    public abstract void refreshCache();

    /**
     * Initiates a background process that over time will fill up cache with
     * preload photos.
     */
    public abstract void preloadPhotosInBackground();
}

class ContactPhotoManagerImpl extends ContactPhotoManager implements Callback {
    private static final String LOADER_THREAD_NAME = "ContactPhotoLoader";

    /**
     * Type of message sent by the UI thread to itself to indicate that some photos
     * need to be loaded.
     */
    private static final int MESSAGE_REQUEST_LOADING = 1;

    /**
     * Type of message sent by the loader thread to indicate that some photos have
     * been loaded.
     */
    private static final int MESSAGE_PHOTOS_LOADED = 2;

    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    private static final String[] COLUMNS = new String[] { Photo._ID, Photo.PHOTO };

    /**
     * Maintains the state of a particular photo.
     */
    private static class BitmapHolder {
        final byte[] bytes;

        volatile boolean fresh;
        Bitmap bitmap;
        SoftReference<Bitmap> bitmapRef;

        public BitmapHolder(byte[] bytes) {
            this.bytes = bytes;
            this.fresh = true;
        }
    }

    private final Context mContext;

    private static final int BITMAP_CACHE_INITIAL_CAPACITY = 32;

    /**
     * An LRU cache for bitmap holders. The cache contains bytes for photos just
     * as they come from the database. It also softly retains the actual bitmap.
     */
    private final BitmapHolderCache mBitmapHolderCache;

    /**
     * Level 2 LRU cache for bitmaps. This is a smaller cache that holds
     * the most recently used bitmaps to save time on decoding
     * them from bytes (the bytes are stored in {@link #mBitmapHolderCache}.
     */
    private final BitmapCache mBitmapCache;

    /**
     * A map from ImageView to the corresponding photo ID. Please note that this
     * photo ID may change before the photo loading request is started.
     */
    private final ConcurrentHashMap<ImageView, Object> mPendingRequests =
            new ConcurrentHashMap<ImageView, Object>();

    /**
     * Handler for messages sent to the UI thread.
     */
    private final Handler mMainThreadHandler = new Handler(this);

    /**
     * Thread responsible for loading photos from the database. Created upon
     * the first request.
     */
    private LoaderThread mLoaderThread;

    /**
     * A gate to make sure we only send one instance of MESSAGE_PHOTOS_NEEDED at a time.
     */
    private boolean mLoadingRequested;

    /**
     * Flag indicating if the image loading is paused.
     */
    private boolean mPaused;

    public ContactPhotoManagerImpl(Context context) {
        mContext = context;

        Resources resources = context.getResources();
        mBitmapCache = new BitmapCache(
                resources.getInteger(R.integer.config_photo_cache_max_bitmaps));
        mBitmapHolderCache = new BitmapHolderCache(mBitmapCache,
                resources.getInteger(R.integer.config_photo_cache_max_bytes));
    }

    @Override
    public void preloadPhotosInBackground() {
        ensureLoaderThread();
        mLoaderThread.requestPreloading();
    }

    @Override
    public void loadPhoto(ImageView view, long photoId) {
        if (photoId == 0) {
            // No photo is needed
            view.setImageResource(mDefaultResourceId);
            mPendingRequests.remove(view);
        } else {
            loadPhotoByIdOrUri(view, photoId);
        }
    }

    @Override
    public void loadPhoto(ImageView view, Uri photoUri) {
        if (photoUri == null) {
            // No photo is needed
            view.setImageResource(mDefaultResourceId);
            mPendingRequests.remove(view);
        } else {
            loadPhotoByIdOrUri(view, photoUri);
        }
    }

    private void loadPhotoByIdOrUri(ImageView view, Object key) {
        boolean loaded = loadCachedPhoto(view, key);
        if (loaded) {
            mPendingRequests.remove(view);
        } else {
            mPendingRequests.put(view, key);
            if (!mPaused) {
                // Send a request to start loading photos
                requestLoading();
            }
        }
    }

    @Override
    public void refreshCache() {
        for (BitmapHolder holder : mBitmapHolderCache.values()) {
            holder.fresh = false;
        }
    }

    /**
     * Checks if the photo is present in cache.  If so, sets the photo on the view.
     *
     * @return false if the photo needs to be (re)loaded from the provider.
     */
    private boolean loadCachedPhoto(ImageView view, Object key) {
        BitmapHolder holder = mBitmapHolderCache.get(key);
        if (holder == null) {
            // The bitmap has not been loaded - should display the placeholder image.
            view.setImageResource(mDefaultResourceId);
            return false;
        }

        if (holder.bytes == null) {
            view.setImageResource(mDefaultResourceId);
            return holder.fresh;
        }

        // Optionally decode bytes into a bitmap
        inflateBitmap(holder);

        view.setImageBitmap(holder.bitmap);

        // Put the bitmap in the LRU cache
        mBitmapCache.put(key, holder.bitmap);

        // Soften the reference
        holder.bitmap = null;

        return holder.fresh;
    }

    /**
     * If necessary, decodes bytes stored in the holder to Bitmap.  As long as the
     * bitmap is held either by {@link #mBitmapCache} or by a soft reference in
     * the holder, it will not be necessary to decode the bitmap.
     */
    private void inflateBitmap(BitmapHolder holder) {
        byte[] bytes = holder.bytes;
        if (bytes == null || bytes.length == 0) {
            return;
        }

        // Check the soft reference.  If will be retained if the bitmap is also
        // in the LRU cache, so we don't need to check the LRU cache explicitly.
        if (holder.bitmapRef != null) {
            holder.bitmap = holder.bitmapRef.get();
            if (holder.bitmap != null) {
                return;
            }
        }

        try {
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, null);
            holder.bitmap = bitmap;
            holder.bitmapRef = new SoftReference<Bitmap>(bitmap);
        } catch (OutOfMemoryError e) {
            // Do nothing - the photo will appear to be missing
        }
    }

    public void clear() {
        mPendingRequests.clear();
        mBitmapHolderCache.clear();
    }

    @Override
    public void pause() {
        mPaused = true;
    }

    @Override
    public void resume() {
        mPaused = false;
        if (!mPendingRequests.isEmpty()) {
            requestLoading();
        }
    }

    /**
     * Sends a message to this thread itself to start loading images.  If the current
     * view contains multiple image views, all of those image views will get a chance
     * to request their respective photos before any of those requests are executed.
     * This allows us to load images in bulk.
     */
    private void requestLoading() {
        if (!mLoadingRequested) {
            mLoadingRequested = true;
            mMainThreadHandler.sendEmptyMessage(MESSAGE_REQUEST_LOADING);
        }
    }

    /**
     * Processes requests on the main thread.
     */
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MESSAGE_REQUEST_LOADING: {
                mLoadingRequested = false;
                if (!mPaused) {
                    ensureLoaderThread();
                    mLoaderThread.requestLoading();
                }
                return true;
            }

            case MESSAGE_PHOTOS_LOADED: {
                if (!mPaused) {
                    processLoadedImages();
                }
                return true;
            }
        }
        return false;
    }

    public void ensureLoaderThread() {
        if (mLoaderThread == null) {
            mLoaderThread = new LoaderThread(mContext.getContentResolver());
            mLoaderThread.start();
        }
    }

    /**
     * Goes over pending loading requests and displays loaded photos.  If some of the
     * photos still haven't been loaded, sends another request for image loading.
     */
    private void processLoadedImages() {
        Iterator<ImageView> iterator = mPendingRequests.keySet().iterator();
        while (iterator.hasNext()) {
            ImageView view = iterator.next();
            Object key = mPendingRequests.get(view);
            boolean loaded = loadCachedPhoto(view, key);
            if (loaded) {
                iterator.remove();
            }
        }

        softenCache();

        if (!mPendingRequests.isEmpty()) {
            requestLoading();
        }
    }

    /**
     * Removes strong references to loaded bitmaps to allow them to be garbage collected
     * if needed.  Some of the bitmaps will still be retained by {@link #mBitmapCache}.
     */
    private void softenCache() {
        for (BitmapHolder holder : mBitmapHolderCache.values()) {
            holder.bitmap = null;
        }
    }

    /**
     * Stores the supplied bitmap in cache.
     */
    private void cacheBitmap(Object key, byte[] bytes, boolean preloading) {
        BitmapHolder holder = new BitmapHolder(bytes);
        holder.fresh = true;

        // Unless this image is being preloaded, decode it right away while
        // we are still on the background thread.
        if (!preloading) {
            inflateBitmap(holder);
        }

        mBitmapHolderCache.put(key, holder);
    }

    /**
     * Populates an array of photo IDs that need to be loaded.
     */
    private void obtainPhotoIdsAndUrisToLoad(ArrayList<Long> photoIds,
            ArrayList<String> photoIdsAsStrings, ArrayList<Uri> uris) {
        photoIds.clear();
        photoIdsAsStrings.clear();
        uris.clear();

        /*
         * Since the call is made from the loader thread, the map could be
         * changing during the iteration. That's not really a problem:
         * ConcurrentHashMap will allow those changes to happen without throwing
         * exceptions. Since we may miss some requests in the situation of
         * concurrent change, we will need to check the map again once loading
         * is complete.
         */
        Iterator<Object> iterator = mPendingRequests.values().iterator();
        while (iterator.hasNext()) {
            Object key = iterator.next();
            BitmapHolder holder = mBitmapHolderCache.get(key);
            if (holder == null || !holder.fresh) {
                if (key instanceof Long) {
                    photoIds.add((Long)key);
                    photoIdsAsStrings.add(key.toString());
                } else {
                    uris.add((Uri)key);
                }
            }
        }
    }

    /**
     * The thread that performs loading of photos from the database.
     */
    private class LoaderThread extends HandlerThread implements Callback {
        private static final int BUFFER_SIZE = 1024*16;
        private static final int MESSAGE_PRELOAD_PHOTOS = 0;
        private static final int MESSAGE_LOAD_PHOTOS = 1;

        /**
         * A pause between preload batches that yields to the UI thread.
         */
        private static final int PHOTO_PRELOAD_DELAY = 50;

        /**
         * Number of photos to preload per batch.
         */
        private static final int PRELOAD_BATCH = 25;

        /**
         * Maximum number of photos to preload.  If the cache size is 2Mb and
         * the expected average size of a photo is 4kb, then this number should be 2Mb/4kb = 500.
         */
        private static final int MAX_PHOTOS_TO_PRELOAD = 500;

        private final ContentResolver mResolver;
        private final StringBuilder mStringBuilder = new StringBuilder();
        private final ArrayList<Long> mPhotoIds = Lists.newArrayList();
        private final ArrayList<String> mPhotoIdsAsStrings = Lists.newArrayList();
        private final ArrayList<Uri> mPhotoUris = Lists.newArrayList();
        private ArrayList<Long> mPreloadPhotoIds = Lists.newArrayList();

        private Handler mLoaderThreadHandler;
        private byte mBuffer[];

        private static final int PRELOAD_STATUS_NOT_STARTED = 0;
        private static final int PRELOAD_STATUS_IN_PROGRESS = 1;
        private static final int PRELOAD_STATUS_DONE = 2;

        private int mPreloadStatus = PRELOAD_STATUS_NOT_STARTED;

        public LoaderThread(ContentResolver resolver) {
            super(LOADER_THREAD_NAME);
            mResolver = resolver;
        }

        public void ensureHandler() {
            if (mLoaderThreadHandler == null) {
                mLoaderThreadHandler = new Handler(getLooper(), this);
            }
        }

        /**
         * Kicks off preloading of the next batch of photos on the background thread.
         * Preloading will happen after a delay: we want to yield to the UI thread
         * as much as possible.
         * <p>
         * If preloading is already complete, does nothing.
         */
        public void requestPreloading() {
            if (mPreloadStatus == PRELOAD_STATUS_DONE) {
                return;
            }

            ensureHandler();
            if (mLoaderThreadHandler.hasMessages(MESSAGE_LOAD_PHOTOS)) {
                return;
            }

            mLoaderThreadHandler.sendEmptyMessageDelayed(
                    MESSAGE_PRELOAD_PHOTOS, PHOTO_PRELOAD_DELAY);
        }

        /**
         * Sends a message to this thread to load requested photos.  Cancels a preloading
         * request, if any: we don't want preloading to impede loading of the photos
         * we need to display now.
         */
        public void requestLoading() {
            ensureHandler();
            mLoaderThreadHandler.removeMessages(MESSAGE_PRELOAD_PHOTOS);
            mLoaderThreadHandler.sendEmptyMessage(MESSAGE_LOAD_PHOTOS);
        }

        /**
         * Receives the above message, loads photos and then sends a message
         * to the main thread to process them.
         */
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_PRELOAD_PHOTOS:
                    preloadPhotosInBackground();
                    break;
                case MESSAGE_LOAD_PHOTOS:
                    loadPhotosInBackground();
                    break;
            }
            return true;
        }

        /**
         * The first time it is called, figures out which photos need to be preloaded.
         * Each subsequent call preloads the next batch of photos and requests
         * another cycle of preloading after a delay.  The whole process ends when
         * we either run out of photos to preload or fill up cache.
         */
        private void preloadPhotosInBackground() {
            if (mPreloadStatus == PRELOAD_STATUS_DONE) {
                return;
            }

            if (mPreloadStatus == PRELOAD_STATUS_NOT_STARTED) {
                queryPhotosForPreload();
                if (mPreloadPhotoIds.isEmpty()) {
                    mPreloadStatus = PRELOAD_STATUS_DONE;
                } else {
                    mPreloadStatus = PRELOAD_STATUS_IN_PROGRESS;
                }
                requestPreloading();
                return;
            }

            if (mBitmapHolderCache.isFull()) {
                mPreloadStatus = PRELOAD_STATUS_DONE;
                return;
            }

            mPhotoIds.clear();
            mPhotoIdsAsStrings.clear();

            int count = 0;
            int preloadSize = mPreloadPhotoIds.size();
            while(preloadSize > 0 && mPhotoIds.size() < PRELOAD_BATCH) {
                preloadSize--;
                count++;
                Long photoId = mPreloadPhotoIds.get(preloadSize);
                mPhotoIds.add(photoId);
                mPhotoIdsAsStrings.add(photoId.toString());
                mPreloadPhotoIds.remove(preloadSize);
            }

            loadPhotosFromDatabase(false);

            if (preloadSize == 0) {
                mPreloadStatus = PRELOAD_STATUS_DONE;
            }

            Log.v(TAG, "Preloaded " + count + " photos. Photos in cache: "
                    + mBitmapHolderCache.size()
                    + ". Total size: " + mBitmapHolderCache.getEstimatedSize());

            requestPreloading();
        }

        private void queryPhotosForPreload() {
            Cursor cursor = null;
            try {
                Uri uri = Contacts.CONTENT_URI.buildUpon().appendQueryParameter(
                        ContactsContract.DIRECTORY_PARAM_KEY, String.valueOf(Directory.DEFAULT))
                        .build();
                cursor = mResolver.query(uri, new String[] { Contacts.PHOTO_ID },
                        Contacts.PHOTO_ID + " NOT NULL AND " + Contacts.PHOTO_ID + "!=0",
                        null,
                        Contacts.STARRED + " DESC, " + Contacts.LAST_TIME_CONTACTED + " DESC"
                                + " LIMIT " + MAX_PHOTOS_TO_PRELOAD);

                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        // Insert them in reverse order, because we will be taking
                        // them from the end of the list for loading.
                        mPreloadPhotoIds.add(0, cursor.getLong(0));
                    }
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }

        private void loadPhotosInBackground() {
            obtainPhotoIdsAndUrisToLoad(mPhotoIds, mPhotoIdsAsStrings, mPhotoUris);
            loadPhotosFromDatabase(true);
            loadRemotePhotos();
            requestPreloading();
        }

        private void loadPhotosFromDatabase(boolean preloading) {
            int count = mPhotoIds.size();
            if (count == 0) {
                return;
            }

            // Remove loaded photos from the preload queue: we don't want
            // the preloading process to load them again.
            if (!preloading && mPreloadStatus == PRELOAD_STATUS_IN_PROGRESS) {
                for (int i = 0; i < count; i++) {
                    mPreloadPhotoIds.remove(mPhotoIds.get(i));
                }
                if (mPreloadPhotoIds.isEmpty()) {
                    mPreloadStatus = PRELOAD_STATUS_DONE;
                }
            }

            mStringBuilder.setLength(0);
            mStringBuilder.append(Photo._ID + " IN(");
            for (int i = 0; i < count; i++) {
                if (i != 0) {
                    mStringBuilder.append(',');
                }
                mStringBuilder.append('?');
            }
            mStringBuilder.append(')');

            Cursor cursor = null;
            try {
                cursor = mResolver.query(Data.CONTENT_URI,
                        COLUMNS,
                        mStringBuilder.toString(),
                        mPhotoIdsAsStrings.toArray(EMPTY_STRING_ARRAY),
                        null);

                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        Long id = cursor.getLong(0);
                        byte[] bytes = cursor.getBlob(1);
                        cacheBitmap(id, bytes, preloading);
                        mPhotoIds.remove(id);
                    }
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }

            // Remaining photos were not found in the database - mark the cache accordingly.
            count = mPhotoIds.size();
            for (int i = 0; i < count; i++) {
                cacheBitmap(mPhotoIds.get(i), null, preloading);
            }

            mMainThreadHandler.sendEmptyMessage(MESSAGE_PHOTOS_LOADED);
        }

        private void loadRemotePhotos() {
            int count = mPhotoUris.size();
            for (int i = 0; i < count; i++) {
                Uri uri = mPhotoUris.get(i);
                if (mBuffer == null) {
                    mBuffer = new byte[BUFFER_SIZE];
                }
                try {
                    InputStream is = mResolver.openInputStream(uri);
                    if (is != null) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        try {
                            int size;
                            while ((size = is.read(mBuffer)) != -1) {
                                baos.write(mBuffer, 0, size);
                            }
                        } finally {
                            is.close();
                        }
                        cacheBitmap(uri, baos.toByteArray(), false);
                        mMainThreadHandler.sendEmptyMessage(MESSAGE_PHOTOS_LOADED);
                    } else {
                        Log.v(TAG, "Cannot load photo " + uri);
                        cacheBitmap(uri, null, false);
                    }
                } catch (Exception ex) {
                    Log.v(TAG, "Cannot load photo " + uri, ex);
                    cacheBitmap(uri, null, false);
                }
            }
        }
    }

    /**
     * An LRU cache of {@link BitmapHolder}'s.  It estimates the total size of
     * loaded bitmaps and caps that number.
     */
    private static class BitmapHolderCache extends LinkedHashMap<Object, BitmapHolder> {
        private final BitmapCache mBitmapCache;
        private final int mMaxBytes;
        private final int mRedZoneBytes;
        private int mEstimatedBytes;

        public BitmapHolderCache(BitmapCache bitmapCache, int maxBytes) {
            super(BITMAP_CACHE_INITIAL_CAPACITY, 0.75f, true);
            this.mBitmapCache = bitmapCache;
            mMaxBytes = maxBytes;
            mRedZoneBytes = (int) (mMaxBytes * 0.75);
        }

        // Leave unsynchronized: if the result is a bit off, that's ok
        public boolean isFull() {
            return mEstimatedBytes > mRedZoneBytes;
        }

        public int getEstimatedSize() {
            return mEstimatedBytes;
        }

        @Override
        public synchronized BitmapHolder get(Object key) {
            return super.get(key);
        }

        @Override
        public synchronized BitmapHolder put(Object key, BitmapHolder newValue) {
            BitmapHolder oldValue = get(key);
            if (oldValue != null && oldValue.bytes != null) {
                mEstimatedBytes -= oldValue.bytes.length;
            }
            if (newValue.bytes != null) {
                mEstimatedBytes += newValue.bytes.length;
            }
            return super.put(key, newValue);
        }

        @Override
        public BitmapHolder remove(Object key) {
            BitmapHolder value = get(key);
            if (value != null && value.bytes != null) {
                mEstimatedBytes -= value.bytes.length;
            }
            mBitmapCache.remove(key);
            return super.remove(key);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<Object, BitmapHolder> eldest) {
            return mEstimatedBytes > mMaxBytes;
        }
    }

    /**
     * An LRU cache of bitmaps.  These are the most recently used bitmaps that we want
     * to protect from GC. The rest of bitmaps are softly retained and will be
     * gradually released by GC.
     */
    private static class BitmapCache extends LinkedHashMap<Object, Bitmap> {
        private int mMaxEntries;

        public BitmapCache(int maxEntries) {
            super(BITMAP_CACHE_INITIAL_CAPACITY, 0.75f, true);
            mMaxEntries = maxEntries;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<Object, Bitmap> eldest) {
            return size() > mMaxEntries;
        }
    }
}
