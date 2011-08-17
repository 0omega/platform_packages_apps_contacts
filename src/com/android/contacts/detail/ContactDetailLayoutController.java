/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.contacts.detail;

import com.android.contacts.ContactLoader;
import com.android.contacts.R;
import com.android.contacts.activities.ContactDetailActivity.FragmentKeyListener;

import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.Context;
import android.os.Bundle;
import android.support.v4.view.ViewPager;
import android.support.v4.view.ViewPager.OnPageChangeListener;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

/**
 * Determines the layout of the contact card.
 */
public class ContactDetailLayoutController {

    private static final String KEY_CONTACT_HAS_UPDATES = "contactHasUpdates";
    private static final String KEY_CURRENT_PAGE_INDEX = "currentPageIndex";

    /**
     * There are 3 possible layouts for the contact detail screen:
     * 1. TWO_COLUMN - Tall and wide screen so the 2 pages can be shown side-by-side
     * 2. VIEW_PAGER_AND_TAB_CAROUSEL - Tall and narrow screen to allow swipe between the 2 pages
     * 3. FRAGMENT_CAROUSEL- Short and wide screen to allow half of the other page to show at a time
     */
    private enum LayoutMode {
        TWO_COLUMN, VIEW_PAGER_AND_TAB_CAROUSEL, FRAGMENT_CAROUSEL,
    }

    private final LayoutInflater mLayoutInflater;
    private final FragmentManager mFragmentManager;

    private ContactDetailFragment mDetailFragment;
    private ContactDetailUpdatesFragment mUpdatesFragment;

    private View mDetailFragmentView;
    private View mUpdatesFragmentView;

    private final ViewPager mViewPager;
    private final ContactDetailTabCarousel mTabCarousel;
    private ContactDetailViewPagerAdapter mViewPagerAdapter;

    private ContactDetailFragmentCarousel mFragmentCarousel;

    private ContactDetailFragment.Listener mContactDetailFragmentListener;

    private ContactLoader.Result mContactData;

    private boolean mContactHasUpdates;

    private LayoutMode mLayoutMode;

    public ContactDetailLayoutController(Context context, Bundle savedState,
            FragmentManager fragmentManager, View viewContainer, ContactDetailFragment.Listener
            contactDetailFragmentListener) {

        if (fragmentManager == null) {
            throw new IllegalStateException("Cannot initialize a ContactDetailLayoutController "
                    + "without a non-null FragmentManager");
        }

        mLayoutInflater = (LayoutInflater) context.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        mFragmentManager = fragmentManager;
        mContactDetailFragmentListener = contactDetailFragmentListener;

        // Retrieve views in case this is view pager and carousel mode
        mViewPager = (ViewPager) viewContainer.findViewById(R.id.pager);
        mTabCarousel = (ContactDetailTabCarousel) viewContainer.findViewById(R.id.tab_carousel);

        // Retrieve view in case this is in fragment carousel mode
        mFragmentCarousel = (ContactDetailFragmentCarousel) viewContainer.findViewById(
                R.id.fragment_carousel);

        // Retrieve container views in case they are already in the XML layout
        mDetailFragmentView = viewContainer.findViewById(R.id.about_fragment_container);
        mUpdatesFragmentView = viewContainer.findViewById(R.id.updates_fragment_container);

        // Determine the layout mode based on the presence of certain views in the layout XML.
        if (mViewPager != null) {
            mLayoutMode = LayoutMode.VIEW_PAGER_AND_TAB_CAROUSEL;
        } else {
            mLayoutMode = (mFragmentCarousel != null) ? LayoutMode.FRAGMENT_CAROUSEL :
                    LayoutMode.TWO_COLUMN;
        }

        initialize(savedState);
    }

    private void initialize(Bundle savedState) {
        boolean fragmentsAddedToFragmentManager = true;
        mDetailFragment = (ContactDetailFragment) mFragmentManager.findFragmentByTag(
                ContactDetailViewPagerAdapter.ABOUT_FRAGMENT_TAG);
        mUpdatesFragment = (ContactDetailUpdatesFragment) mFragmentManager.findFragmentByTag(
                ContactDetailViewPagerAdapter.UPDTES_FRAGMENT_TAG);

        // If the detail fragment was found in the {@link FragmentManager} then we don't need to add
        // it again. Otherwise, create the fragments dynamically and remember to add them to the
        // {@link FragmentManager}.
        if (mDetailFragment == null) {
            mDetailFragment = new ContactDetailFragment();
            mUpdatesFragment = new ContactDetailUpdatesFragment();
            fragmentsAddedToFragmentManager = false;
        }

        mDetailFragment.setListener(mContactDetailFragmentListener);

        // Read from savedState if possible
        int currentPageIndex = 0;
        if (savedState != null) {
            mContactHasUpdates = savedState.getBoolean(KEY_CONTACT_HAS_UPDATES);
            currentPageIndex = savedState.getInt(KEY_CURRENT_PAGE_INDEX, 0);
        }

        switch (mLayoutMode) {
            case VIEW_PAGER_AND_TAB_CAROUSEL: {
                // Inflate 2 view containers to pass in as children to the {@link ViewPager},
                // which will in turn be the parents to the mDetailFragment and mUpdatesFragment
                // since the fragments must have the same parent view IDs in both landscape and
                // portrait layouts.
                ViewGroup detailContainer = (ViewGroup) mLayoutInflater.inflate(
                        R.layout.contact_detail_about_fragment_container, mViewPager, false);
                ViewGroup updatesContainer = (ViewGroup) mLayoutInflater.inflate(
                        R.layout.contact_detail_updates_fragment_container, mViewPager, false);

                mViewPagerAdapter = new ContactDetailViewPagerAdapter();
                mViewPagerAdapter.setAboutFragmentView(detailContainer);
                mViewPagerAdapter.setUpdatesFragmentView(updatesContainer);

                mViewPager.addView(detailContainer);
                mViewPager.addView(updatesContainer);
                mViewPager.setAdapter(mViewPagerAdapter);
                mViewPager.setOnPageChangeListener(mOnPageChangeListener);

                if (!fragmentsAddedToFragmentManager) {
                    FragmentTransaction transaction = mFragmentManager.beginTransaction();
                    transaction.add(R.id.about_fragment_container, mDetailFragment,
                            ContactDetailViewPagerAdapter.ABOUT_FRAGMENT_TAG);
                    transaction.add(R.id.updates_fragment_container, mUpdatesFragment,
                            ContactDetailViewPagerAdapter.UPDTES_FRAGMENT_TAG);
                    transaction.commit();
                    mFragmentManager.executePendingTransactions();
                }

                mTabCarousel.setListener(mTabCarouselListener);
                TabCarouselScrollManager.bind(mTabCarousel, mDetailFragment, mUpdatesFragment);
                mViewPager.setCurrentItem(currentPageIndex);
                break;
            }
            case TWO_COLUMN: {
                if (!fragmentsAddedToFragmentManager) {
                    FragmentTransaction transaction = mFragmentManager.beginTransaction();
                    transaction.add(R.id.about_fragment_container, mDetailFragment,
                            ContactDetailViewPagerAdapter.ABOUT_FRAGMENT_TAG);
                    transaction.add(R.id.updates_fragment_container, mUpdatesFragment,
                            ContactDetailViewPagerAdapter.UPDTES_FRAGMENT_TAG);
                    transaction.commit();
                    mFragmentManager.executePendingTransactions();
                }
                break;
            }
            case FRAGMENT_CAROUSEL: {
                // Add the fragments to the fragment containers in the carousel using a
                // {@link FragmentTransaction} if they haven't already been added to the
                // {@link FragmentManager}.
                if (!fragmentsAddedToFragmentManager) {
                    FragmentTransaction transaction = mFragmentManager.beginTransaction();
                    transaction.add(R.id.about_fragment_container, mDetailFragment,
                            ContactDetailViewPagerAdapter.ABOUT_FRAGMENT_TAG);
                    transaction.add(R.id.updates_fragment_container, mUpdatesFragment,
                            ContactDetailViewPagerAdapter.UPDTES_FRAGMENT_TAG);
                    transaction.commit();
                    mFragmentManager.executePendingTransactions();
                }

                mFragmentCarousel.setFragmentViews(mDetailFragmentView, mUpdatesFragmentView);
                mFragmentCarousel.setFragments(mDetailFragment, mUpdatesFragment);
                mFragmentCarousel.setCurrentPage(currentPageIndex);
                break;
            }
        }

        // Setup the layout if we already have a saved state
        if (savedState != null) {
            if (mContactHasUpdates) {
                showContactWithUpdates();
            } else {
                showContactWithoutUpdates();
            }
        }
    }

    public void setContactData(ContactLoader.Result data) {
        mContactData = data;
        mContactHasUpdates = !data.getStreamItems().isEmpty();
        if (mContactHasUpdates) {
            showContactWithUpdates();
        } else {
            showContactWithoutUpdates();
        }
    }

    /**
     * Setup the layout for the contact with updates. Pass in the index of the current page to
     * select or null if the current selection should be left as is.
     */
    private void showContactWithUpdates() {
        if (mContactData == null) {
            return;
        }
        switch (mLayoutMode) {
            case TWO_COLUMN: {
                // Set the contact data (hide the static photo because the photo will already be in
                // the header that scrolls with contact details).
                mDetailFragment.setShowStaticPhoto(false);
                // Show the updates fragment
                mUpdatesFragmentView.setVisibility(View.VISIBLE);
                break;
            }
            case VIEW_PAGER_AND_TAB_CAROUSEL: {
                // Update and show the tab carousel
                mTabCarousel.loadData(mContactData);
                mTabCarousel.setVisibility(View.VISIBLE);
                // Update ViewPager to allow swipe between all the fragments (to see updates)
                mViewPagerAdapter.enableSwipe(true);
                break;
            }
            case FRAGMENT_CAROUSEL: {
                // Allow swiping between all fragments
                mFragmentCarousel.enableSwipe(true);
                break;
            }
            default:
                throw new IllegalStateException("Invalid LayoutMode " + mLayoutMode);
        }

        mDetailFragment.setData(mContactData.getLookupUri(), mContactData);
        mUpdatesFragment.setData(mContactData.getLookupUri(), mContactData);
    }

    private void showContactWithoutUpdates() {
        if (mContactData == null) {
            return;
        }
        switch (mLayoutMode) {
            case TWO_COLUMN:
                // Show the static photo which is next to the list of scrolling contact details
                mDetailFragment.setShowStaticPhoto(true);
                // Hide the updates fragment
                mUpdatesFragmentView.setVisibility(View.GONE);
                break;
            case VIEW_PAGER_AND_TAB_CAROUSEL:
                // Hide the tab carousel
                mTabCarousel.setVisibility(View.GONE);
                // Update ViewPager to disable swipe so that it only shows the detail fragment
                // and switch to the detail fragment
                mViewPagerAdapter.enableSwipe(false);
                mViewPager.setCurrentItem(0);
                break;
            case FRAGMENT_CAROUSEL: {
                // Disable swipe so only the detail fragment shows
                mFragmentCarousel.enableSwipe(false);
                break;
            }
            default:
                throw new IllegalStateException("Invalid LayoutMode " + mLayoutMode);
        }

        mDetailFragment.setData(mContactData.getLookupUri(), mContactData);
    }

    public FragmentKeyListener getCurrentPage() {
        switch (getCurrentPageIndex()) {
            case 0:
                return mDetailFragment;
            case 1:
                return mUpdatesFragment;
            default:
                throw new IllegalStateException("Invalid current item for ViewPager");
        }
    }

    private int getCurrentPageIndex() {
        // If the contact has social updates, then retrieve the current page based on the
        // {@link ViewPager} or fragment carousel.
        if (mContactHasUpdates) {
            if (mViewPager != null) {
                return mViewPager.getCurrentItem();
            } else if (mFragmentCarousel != null) {
                return mFragmentCarousel.getCurrentPage();
            }
        }
        // Otherwise return the default page (detail fragment).
        return 0;
    }

    public void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(KEY_CONTACT_HAS_UPDATES, mContactHasUpdates);
        outState.putInt(KEY_CURRENT_PAGE_INDEX, getCurrentPageIndex());
    }

    private OnPageChangeListener mOnPageChangeListener = new OnPageChangeListener() {

        @Override
        public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
            // The user is horizontally dragging the {@link ViewPager}, so send
            // these scroll changes to the tab carousel. Ignore these events though if the carousel
            // is actually controlling the {@link ViewPager} scrolls because it will already be
            // in the correct position.
            if (mViewPager.isFakeDragging()) {
                return;
            }
            int x = (int) ((position + positionOffset) *
                    mTabCarousel.getAllowedHorizontalScrollLength());
            mTabCarousel.scrollTo(x, 0);
        }

        @Override
        public void onPageSelected(int position) {
            // Since a new page has been selected by the {@link ViewPager},
            // update the tab selection in the carousel.
            mTabCarousel.setCurrentTab(position);
        }

        @Override
        public void onPageScrollStateChanged(int state) {}

    };

    private ContactDetailTabCarousel.Listener mTabCarouselListener =
            new ContactDetailTabCarousel.Listener() {

        @Override
        public void onTouchDown() {
            // The user just started scrolling the carousel, so begin "fake dragging" the
            // {@link ViewPager} if it's not already doing so.
            if (mViewPager.isFakeDragging()) {
                return;
            }
            mViewPager.beginFakeDrag();
        }

        @Override
        public void onTouchUp() {
            // The user just stopped scrolling the carousel, so stop "fake dragging" the
            // {@link ViewPager} if was doing so before.
            if (mViewPager.isFakeDragging()) {
                mViewPager.endFakeDrag();
            }
        }

        @Override
        public void onScrollChanged(int l, int t, int oldl, int oldt) {
            // The user is scrolling the carousel, so send the scroll deltas to the
            // {@link ViewPager} so it can move in sync.
            if (mViewPager.isFakeDragging()) {
                mViewPager.fakeDragBy(oldl-l);
            }
        }

        @Override
        public void onTabSelected(int position) {
            // The user selected a tab, so update the {@link ViewPager}
            mViewPager.setCurrentItem(position);
        }
    };
}
