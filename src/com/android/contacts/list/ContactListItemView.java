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

package com.android.contacts.list;

import com.android.contacts.ContactPresenceIconUtil;
import com.android.contacts.ContactStatusUtil;
import com.android.contacts.R;
import com.android.contacts.format.DisplayNameFormatter;
import com.android.contacts.format.PrefixHighlighter;
import com.android.contacts.widget.TextWithHighlightingFactory;

import android.content.Context;
import android.content.res.TypedArray;
import android.database.CharArrayBuffer;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.provider.ContactsContract.Contacts;
import android.text.TextUtils;
import android.text.TextUtils.TruncateAt;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView.SelectionBoundsAdjuster;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import android.widget.QuickContactBadge;
import android.widget.TextView;

/**
 * A custom view for an item in the contact list.
 * The view contains the contact's photo, a set of text views (for name, status, etc...) and
 * icons for presence and call.
 * The view uses no XML file for layout and all the measurements and layouts are done
 * in the onMeasure and onLayout methods.
 *
 * The layout puts the contact's photo on the right side of the view, the call icon (if present)
 * to the left of the photo, the text lines are aligned to the left and the presence icon (if
 * present) is set to the left of the status line.
 *
 * The layout also supports a header (used as a header of a group of contacts) that is above the
 * contact's data and a divider between contact view.
 */

public class ContactListItemView extends ViewGroup
        implements SelectionBoundsAdjuster {

    private static final int QUICK_CONTACT_BADGE_STYLE =
            com.android.internal.R.attr.quickContactBadgeStyleWindowMedium;

    protected final Context mContext;

    // Style values for layout and appearance
    private final int mPreferredHeight;
    private final int mVerticalDividerMargin;
    private final int mPaddingTop;
    private final int mPaddingRight;
    private final int mPaddingBottom;
    private final int mPaddingLeft;
    private final int mGapBetweenImageAndText;
    private final int mGapBetweenLabelAndData;
    private final int mCallButtonPadding;
    private final int mPresenceIconMargin;
    private final int mPresenceIconSize;
    private final int mHeaderTextColor;
    private final int mHeaderTextIndent;
    private final int mHeaderTextSize;
    private final int mHeaderUnderlineHeight;
    private final int mHeaderUnderlineColor;

    private Drawable mActivatedBackgroundDrawable;

    // Horizontal divider between contact views.
    private boolean mHorizontalDividerVisible = true;
    private Drawable mHorizontalDividerDrawable;
    private int mHorizontalDividerHeight;

    // Vertical divider between the call icon and the text.
    private boolean mVerticalDividerVisible;
    private Drawable mVerticalDividerDrawable;
    private int mVerticalDividerWidth;

    // Header layout data
    private boolean mHeaderVisible;
    private View mHeaderDivider;
    private int mHeaderBackgroundHeight;
    private TextView mHeaderTextView;

    // The views inside the contact view
    private boolean mQuickContactEnabled = true;
    private QuickContactBadge mQuickContact;
    private ImageView mPhotoView;
    private TextView mNameTextView;
    private TextView mPhoneticNameTextView;
    private DontPressWithParentImageView mCallButton;
    private TextView mLabelView;
    private TextView mDataView;
    private TextView mSnippetView;
    private TextView mStatusView;
    private TextView mCountView;
    private ImageView mPresenceIcon;

    private char[] mHighlightedPrefix;

    private int mDefaultPhotoViewSize;
    /**
     * Can be effective even when {@link #mPhotoView} is null, as we want to have horizontal padding
     * to align other data in this View.
     */
    private int mPhotoViewWidth;
    /**
     * Can be effective even when {@link #mPhotoView} is null, as we want to have vertical padding.
     */
    private int mPhotoViewHeight;

    /**
     * Only effective when {@link #mPhotoView} is null.
     * When true all the Views on the right side of the photo should have horizontal padding on
     * those left assuming there is a photo.
     */
    private boolean mKeepHorizontalPaddingForPhotoView;
    /**
     * Only effective when {@link #mPhotoView} is null.
     */
    private boolean mKeepVerticalPaddingForPhotoView;

    /**
     * True when {@link #mPhotoViewWidth} and {@link #mPhotoViewHeight} are ready for being used.
     * False indicates those values should be updated before being used in position calculation.
     */
    private boolean mPhotoViewWidthAndHeightAreReady = false;

    private int mNameTextViewHeight;
    private int mPhoneticNameTextViewHeight;
    private int mLabelTextViewHeight;
    private int mSnippetTextViewHeight;
    private int mStatusTextViewHeight;

    private OnClickListener mCallButtonClickListener;
    private CharArrayBuffer mDataBuffer = new CharArrayBuffer(128);
    private CharArrayBuffer mPhoneticNameBuffer = new CharArrayBuffer(128);

    private boolean mActivatedStateSupported;

    private Rect mBoundsWithoutHeader = new Rect();

    /** A helper used to highlight a prefix in a text field. */
    private PrefixHighlighter mPrefixHighligher;
    /** A helper used to format display names. */
    private DisplayNameFormatter mDisplayNameFormatter;

    /**
     * Special class to allow the parent to be pressed without being pressed itself.
     * This way the line of a tab can be pressed, but the image itself is not.
     */
    // TODO: understand this
    private static class DontPressWithParentImageView extends ImageView {

        public DontPressWithParentImageView(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        @Override
        public void setPressed(boolean pressed) {
            // If the parent is pressed, do not set to pressed.
            if (pressed && ((View) getParent()).isPressed()) {
                return;
            }
            super.setPressed(pressed);
        }
    }

    public ContactListItemView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;

        // Read all style values
        TypedArray a = getContext().obtainStyledAttributes(attrs, R.styleable.ContactListItemView);
        mPreferredHeight = a.getDimensionPixelSize(
                R.styleable.ContactListItemView_list_item_height, 0);
        mActivatedBackgroundDrawable = a.getDrawable(
                R.styleable.ContactListItemView_activated_background);
        mHorizontalDividerDrawable = a.getDrawable(
                R.styleable.ContactListItemView_list_item_divider);
        mVerticalDividerMargin = a.getDimensionPixelOffset(
                R.styleable.ContactListItemView_list_item_vertical_divider_margin, 0);
        mPaddingTop = a.getDimensionPixelOffset(
                R.styleable.ContactListItemView_list_item_padding_top, 0);
        mPaddingBottom = a.getDimensionPixelOffset(
                R.styleable.ContactListItemView_list_item_padding_bottom, 0);
        mPaddingLeft = a.getDimensionPixelOffset(
                R.styleable.ContactListItemView_list_item_padding_left, 0);
        mPaddingRight = a.getDimensionPixelOffset(
                R.styleable.ContactListItemView_list_item_padding_right, 0);
        mGapBetweenImageAndText = a.getDimensionPixelOffset(
                R.styleable.ContactListItemView_list_item_gap_between_image_and_text, 0);
        mGapBetweenLabelAndData = a.getDimensionPixelOffset(
                R.styleable.ContactListItemView_list_item_gap_between_label_and_data, 0);
        mCallButtonPadding = a.getDimensionPixelOffset(
                R.styleable.ContactListItemView_list_item_call_button_padding, 0);
        mPresenceIconMargin = a.getDimensionPixelOffset(
                R.styleable.ContactListItemView_list_item_presence_icon_margin, 4);
        mPresenceIconSize = a.getDimensionPixelOffset(
                R.styleable.ContactListItemView_list_item_presence_icon_size, 16);
        mDefaultPhotoViewSize = a.getDimensionPixelOffset(
                R.styleable.ContactListItemView_list_item_photo_size, 0);
        mHeaderTextIndent = a.getDimensionPixelOffset(
                R.styleable.ContactListItemView_list_item_header_text_indent, 0);
        mHeaderTextColor = a.getColor(
                R.styleable.ContactListItemView_list_item_header_text_color, Color.BLACK);
        mHeaderTextSize = a.getDimensionPixelSize(
                R.styleable.ContactListItemView_list_item_header_text_size, 12);
        mHeaderBackgroundHeight = a.getDimensionPixelSize(
                R.styleable.ContactListItemView_list_item_header_height, 30);
        mHeaderUnderlineHeight = a.getDimensionPixelSize(
                R.styleable.ContactListItemView_list_item_header_underline_height, 1);
        mHeaderUnderlineColor = a.getColor(
                R.styleable.ContactListItemView_list_item_header_underline_color, 0);

        mPrefixHighligher = new PrefixHighlighter(
                a.getColor(R.styleable.ContactListItemView_list_item_prefix_highlight_color,
                        Color.GREEN));
        a.recycle();

        mHorizontalDividerHeight = mHorizontalDividerDrawable.getIntrinsicHeight();

        if (mActivatedBackgroundDrawable != null) {
            mActivatedBackgroundDrawable.setCallback(this);
        }

        mDisplayNameFormatter = new DisplayNameFormatter(mPrefixHighligher);
    }

    /**
     * Installs a call button listener.
     */
    public void setOnCallButtonClickListener(OnClickListener callButtonClickListener) {
        mCallButtonClickListener = callButtonClickListener;
    }

    public void setTextWithHighlightingFactory(TextWithHighlightingFactory factory) {
        mDisplayNameFormatter.setTextWithHighlightingFactory(factory);
    }

    public void setUnknownNameText(CharSequence unknownNameText) {
        mDisplayNameFormatter.setUnknownNameText(unknownNameText);
    }

    public void setQuickContactEnabled(boolean flag) {
        mQuickContactEnabled = flag;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // We will match parent's width and wrap content vertically, but make sure
        // height is no less than listPreferredItemHeight.
        int width = resolveSize(0, widthMeasureSpec);
        int height = 0;
        int preferredHeight = mPreferredHeight;

        mNameTextViewHeight = 0;
        mPhoneticNameTextViewHeight = 0;
        mLabelTextViewHeight = 0;
        mSnippetTextViewHeight = 0;
        mStatusTextViewHeight = 0;

        // Go over all visible text views and add their heights to get the total height
        if (isVisible(mNameTextView)) {
            mNameTextView.measure(0, 0);
            mNameTextViewHeight = mNameTextView.getMeasuredHeight();
        }

        if (isVisible(mPhoneticNameTextView)) {
            mPhoneticNameTextView.measure(0, 0);
            mPhoneticNameTextViewHeight = mPhoneticNameTextView.getMeasuredHeight();
        }

        if (isVisible(mLabelView)) {
            mLabelView.measure(0, 0);
            mLabelTextViewHeight = mLabelView.getMeasuredHeight();
        }

        // Label view height is the biggest of the label text view and the data text view
        if (isVisible(mDataView)) {
            mDataView.measure(0, 0);
            mLabelTextViewHeight = Math.max(mLabelTextViewHeight, mDataView.getMeasuredHeight());
        }

        if (isVisible(mSnippetView)) {
            mSnippetView.measure(0, 0);
            mSnippetTextViewHeight = mSnippetView.getMeasuredHeight();
        }

        // Status view height is the biggest of the text view and the presence icon
        if (isVisible(mPresenceIcon)) {
            mPresenceIcon.measure(mPresenceIconSize, mPresenceIconSize);
            mStatusTextViewHeight = mPresenceIcon.getMeasuredHeight();
        }

        if (isVisible(mStatusView)) {
            mStatusView.measure(0, 0);
            mStatusTextViewHeight = Math.max(mStatusTextViewHeight,
                    mStatusView.getMeasuredHeight());
        }

        // Calculate height including padding
        height += mNameTextViewHeight + mPhoneticNameTextViewHeight + mLabelTextViewHeight +
                mSnippetTextViewHeight + mStatusTextViewHeight + mPaddingTop + mPaddingBottom;

        if (isVisible(mCallButton)) {
            mCallButton.measure(0, 0);
        }

        // Make sure the height is at least as high as the photo
        ensurePhotoViewSize();
        height = Math.max(height, mPhotoViewHeight + mPaddingBottom + mPaddingTop);

        // Add horizontal divider height
        if (mHorizontalDividerVisible) {
            height += mHorizontalDividerHeight;
            preferredHeight += mHorizontalDividerHeight;
        }

        // Make sure height is at least the preferred height
        height = Math.max(height, preferredHeight);

        // Add the height of the header if visible
        if (mHeaderVisible) {
            mHeaderTextView.measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(mHeaderBackgroundHeight, MeasureSpec.EXACTLY));
            if (mCountView != null) {
                mCountView.measure(
                        MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(mHeaderBackgroundHeight, MeasureSpec.EXACTLY));
            }
            mHeaderBackgroundHeight = Math.max(mHeaderBackgroundHeight,
                    mHeaderTextView.getMeasuredHeight());
            height += (mHeaderBackgroundHeight + mHeaderUnderlineHeight);
        }

        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int height = bottom - top;
        int width = right - left;

        // Determine the vertical bounds by laying out the header first.
        int topBound = 0;
        int bottomBound = height;
        int leftBound = mPaddingLeft;

        // Put the header in the top of the contact view (Text + underline view)
        if (mHeaderVisible) {
            mHeaderTextView.layout(leftBound + mHeaderTextIndent,
                    0,
                    width - mPaddingRight,
                    mHeaderBackgroundHeight);
            if (mCountView != null) {
                mCountView.layout(width - mPaddingRight - mCountView.getMeasuredWidth(),
                        0,
                        width - mPaddingRight,
                        mHeaderBackgroundHeight);
            }
            mHeaderDivider.layout(leftBound,
                    mHeaderBackgroundHeight,
                    width - mPaddingRight,
                    mHeaderBackgroundHeight + mHeaderUnderlineHeight);
            topBound += (mHeaderBackgroundHeight + mHeaderUnderlineHeight);
        }

        // Put horizontal divider at the bottom
        if (mHorizontalDividerVisible) {
            mHorizontalDividerDrawable.setBounds(
                    leftBound,
                    height - mHorizontalDividerHeight,
                    width - mPaddingRight,
                    height);
            bottomBound -= mHorizontalDividerHeight;
        }

        mBoundsWithoutHeader.set(0, topBound, width, bottomBound);

        if (mActivatedStateSupported) {
            mActivatedBackgroundDrawable.setBounds(mBoundsWithoutHeader);
        }

        // Set the top/bottom paddings
        topBound += mPaddingTop;
        bottomBound -= mPaddingBottom;

        // Set contact layout:
        // Photo on the right, call button to the left of the photo
        // Text aligned to the left along with the presence status.

        // layout the photo and call button.
        int rightBound = layoutRightSide(height, topBound, bottomBound, width - mPaddingRight);

        // Center text vertically
        int totalTextHeight = mNameTextViewHeight + mPhoneticNameTextViewHeight +
                mLabelTextViewHeight + mSnippetTextViewHeight + mStatusTextViewHeight;
        int textTopBound = (bottomBound + topBound - totalTextHeight) / 2;

        // Layout all text view and presence icon
        // Put name TextView first
        if (isVisible(mNameTextView)) {
            mNameTextView.layout(leftBound,
                    textTopBound,
                    rightBound,
                    textTopBound + mNameTextViewHeight);
            textTopBound += mNameTextViewHeight;
        }

        // Presence and status
        int statusLeftBound = leftBound;
        if (isVisible(mPresenceIcon)) {
            int iconWidth = mPresenceIcon.getMeasuredWidth();
            mPresenceIcon.layout(
                    leftBound,
                    textTopBound,
                    leftBound + iconWidth,
                    textTopBound + mStatusTextViewHeight);
            statusLeftBound += (iconWidth + mPresenceIconMargin);
        }

        if (isVisible(mStatusView)) {
            mStatusView.layout(statusLeftBound,
                    textTopBound,
                    rightBound,
                    textTopBound + mStatusTextViewHeight);
        }

        if (isVisible(mStatusView) || isVisible(mPresenceIcon)) {
            textTopBound += mStatusTextViewHeight;
        }

        // Rest of text views
        int dataLeftBound = leftBound;
        if (isVisible(mPhoneticNameTextView)) {
            mPhoneticNameTextView.layout(leftBound,
                    textTopBound,
                    rightBound,
                    textTopBound + mPhoneticNameTextViewHeight);
            textTopBound += mPhoneticNameTextViewHeight;
        }

        if (isVisible(mLabelView)) {
            dataLeftBound = leftBound + mLabelView.getMeasuredWidth();
            mLabelView.layout(leftBound,
                    textTopBound,
                    dataLeftBound,
                    textTopBound + mLabelTextViewHeight);
            dataLeftBound += mGapBetweenLabelAndData;
        }

        if (isVisible(mDataView)) {
            mDataView.layout(dataLeftBound,
                    textTopBound,
                    rightBound,
                    textTopBound + mLabelTextViewHeight);
        }
        if (isVisible(mLabelView) || isVisible(mDataView)) {
            textTopBound += mLabelTextViewHeight;
        }

        if (isVisible(mSnippetView)) {
            mSnippetView.layout(leftBound,
                    textTopBound,
                    rightBound,
                    textTopBound + mSnippetTextViewHeight);
        }
    }

    /**
     * Performs layout of the right side of the view
     *
     * @return new right boundary
     */
    protected int layoutRightSide(int height, int topBound, int bottomBound, int rightBound) {

        // Photo is the right most view, set it up

        View photoView = mQuickContact != null ? mQuickContact : mPhotoView;
        if (photoView != null) {
            // Center the photo vertically
            int photoTop = topBound + (bottomBound - topBound - mPhotoViewHeight) / 2;
            photoView.layout(
                    rightBound - mPhotoViewWidth,
                    photoTop,
                    rightBound,
                    photoTop + mPhotoViewHeight);
            rightBound -= (mPhotoViewWidth + mGapBetweenImageAndText);
        }

        // Put call button and vertical divider
        if (isVisible(mCallButton)) {
            int buttonWidth = mCallButton.getMeasuredWidth();
            rightBound -= buttonWidth;
            mCallButton.layout(
                    rightBound,
                    topBound,
                    rightBound + buttonWidth,
                    height - mHorizontalDividerHeight);
            mVerticalDividerVisible = true;
            ensureVerticalDivider();
            rightBound -= mVerticalDividerWidth;
            mVerticalDividerDrawable.setBounds(
                    rightBound,
                    topBound + mVerticalDividerMargin,
                    rightBound + mVerticalDividerWidth,
                    height - mVerticalDividerMargin);
        } else {
            mVerticalDividerVisible = false;
        }

        return rightBound;
    }

    @Override
    public void adjustListItemSelectionBounds(Rect bounds) {
        bounds.top += mBoundsWithoutHeader.top;
        bounds.bottom = bounds.top + mBoundsWithoutHeader.height();
    }

    protected boolean isVisible(View view) {
        return view != null && view.getVisibility() == View.VISIBLE;
    }

    /**
     * Loads the drawable for the vertical divider if it has not yet been loaded.
     */
    private void ensureVerticalDivider() {
        if (mVerticalDividerDrawable == null) {
            mVerticalDividerDrawable = mContext.getResources().getDrawable(
                    R.drawable.divider_vertical_dark);
            mVerticalDividerWidth = mVerticalDividerDrawable.getIntrinsicWidth();
        }
    }

    /**
     * Extracts width and height from the style
     */
    private void ensurePhotoViewSize() {
        if (!mPhotoViewWidthAndHeightAreReady) {
            if (mQuickContactEnabled) {
                TypedArray a = mContext.obtainStyledAttributes(null,
                        com.android.internal.R.styleable.ViewGroup_Layout,
                        QUICK_CONTACT_BADGE_STYLE, 0);
                mPhotoViewWidth = a.getLayoutDimension(
                        android.R.styleable.ViewGroup_Layout_layout_width,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                mPhotoViewHeight = a.getLayoutDimension(
                        android.R.styleable.ViewGroup_Layout_layout_height,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                a.recycle();
            } else if (mPhotoView != null) {
                mPhotoViewWidth = mPhotoViewHeight = getDefaultPhotoViewSize();
            } else {
                final int defaultPhotoViewSize = getDefaultPhotoViewSize();
                mPhotoViewWidth = mKeepHorizontalPaddingForPhotoView ? defaultPhotoViewSize : 0;
                mPhotoViewHeight = mKeepVerticalPaddingForPhotoView ? defaultPhotoViewSize : 0;
            }

            mPhotoViewWidthAndHeightAreReady = true;
        }
    }

    protected void setDefaultPhotoViewSize(int pixels) {
        mDefaultPhotoViewSize = pixels;
    }

    protected int getDefaultPhotoViewSize() {
        return mDefaultPhotoViewSize;
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        if (mActivatedStateSupported) {
            mActivatedBackgroundDrawable.setState(getDrawableState());
        }
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return who == mActivatedBackgroundDrawable || super.verifyDrawable(who);
    }

    @Override
    public void jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState();
        if (mActivatedStateSupported) {
            mActivatedBackgroundDrawable.jumpToCurrentState();
        }
    }

    @Override
    public void dispatchDraw(Canvas canvas) {
        if (mActivatedStateSupported) {
            mActivatedBackgroundDrawable.draw(canvas);
        }
        if (mHorizontalDividerVisible) {
            mHorizontalDividerDrawable.draw(canvas);
        }
        if (mVerticalDividerVisible) {
            mVerticalDividerDrawable.draw(canvas);
        }

        super.dispatchDraw(canvas);
    }

    /**
     * Sets the flag that determines whether a divider should drawn at the bottom
     * of the view.
     */
    public void setDividerVisible(boolean visible) {
        mHorizontalDividerVisible = visible;
    }

    /**
     * Sets section header or makes it invisible if the title is null.
     */
    public void setSectionHeader(String title) {
        if (!TextUtils.isEmpty(title)) {
            if (mHeaderTextView == null) {
                mHeaderTextView = new TextView(mContext);
                mHeaderTextView.setTextColor(mHeaderTextColor);
                mHeaderTextView.setTextSize(mHeaderTextSize);
                mHeaderTextView.setTypeface(mHeaderTextView.getTypeface(), Typeface.BOLD);
                mHeaderTextView.setGravity(Gravity.CENTER_VERTICAL);
                addView(mHeaderTextView);
            }
            if (mHeaderDivider == null) {
                mHeaderDivider = new View(mContext);
                mHeaderDivider.setBackgroundColor(mHeaderUnderlineColor);
                addView(mHeaderDivider);
            }
            mHeaderTextView.setText(title);
            mHeaderTextView.setVisibility(View.VISIBLE);
            mHeaderDivider.setVisibility(View.VISIBLE);
            mHeaderVisible = true;
        } else {
            if (mHeaderTextView != null) {
                mHeaderTextView.setVisibility(View.GONE);
            }
            if (mHeaderDivider != null) {
                mHeaderDivider.setVisibility(View.GONE);
            }
            mHeaderVisible = false;
        }
    }

    /**
     * Returns the quick contact badge, creating it if necessary.
     */
    public QuickContactBadge getQuickContact() {
        if (!mQuickContactEnabled) {
            throw new IllegalStateException("QuickContact is disabled for this view");
        }
        if (mQuickContact == null) {
            mQuickContact = new QuickContactBadge(mContext, null, QUICK_CONTACT_BADGE_STYLE);
            addView(mQuickContact);
            mPhotoViewWidthAndHeightAreReady = false;
        }
        return mQuickContact;
    }

    /**
     * Returns the photo view, creating it if necessary.
     */
    public ImageView getPhotoView() {
        if (mPhotoView == null) {
            if (mQuickContactEnabled) {
                mPhotoView = new ImageView(mContext, null, QUICK_CONTACT_BADGE_STYLE);
            } else {
                mPhotoView = new ImageView(mContext);
            }
            // Quick contact style used above will set a background - remove it
            mPhotoView.setBackgroundDrawable(null);
            addView(mPhotoView);
            mPhotoViewWidthAndHeightAreReady = false;
        }
        return mPhotoView;
    }

    /**
     * Removes the photo view.
     */
    public void removePhotoView() {
        removePhotoView(false, true);
    }

    /**
     * Removes the photo view.
     *
     * @param keepHorizontalPadding True means data on the right side will have
     *            padding on left, pretending there is still a photo view.
     * @param keepVerticalPadding True means the View will have some height
     *            enough for accommodating a photo view.
     */
    public void removePhotoView(boolean keepHorizontalPadding, boolean keepVerticalPadding) {
        mPhotoViewWidthAndHeightAreReady = false;
        mKeepHorizontalPaddingForPhotoView = keepHorizontalPadding;
        mKeepVerticalPaddingForPhotoView = keepVerticalPadding;
        if (mPhotoView != null) {
            removeView(mPhotoView);
            mPhotoView = null;
        }
        if (mQuickContact != null) {
            removeView(mQuickContact);
            mQuickContact = null;
        }
    }

    /**
     * Sets a word prefix that will be highlighted if encountered in fields like
     * name and search snippet.
     * <p>
     * NOTE: must be all upper-case
     */
    public void setHighlightedPrefix(char[] upperCasePrefix) {
        mHighlightedPrefix = upperCasePrefix;
    }

    /**
     * Returns the text view for the contact name, creating it if necessary.
     */
    public TextView getNameTextView() {
        if (mNameTextView == null) {
            mNameTextView = new TextView(mContext);
            mNameTextView.setSingleLine(true);
            mNameTextView.setEllipsize(getTextEllipsis());
            mNameTextView.setTextAppearance(mContext, android.R.style.TextAppearance_Medium);
            mNameTextView.setGravity(Gravity.CENTER_VERTICAL);
            addView(mNameTextView);
        }
        return mNameTextView;
    }

    /**
     * Adds a call button using the supplied arguments as an id and tag.
     */
    public void showCallButton(int id, int tag) {
        if (mCallButton == null) {
            mCallButton = new DontPressWithParentImageView(mContext, null);
            mCallButton.setId(id);
            mCallButton.setOnClickListener(mCallButtonClickListener);
            mCallButton.setBackgroundResource(R.drawable.call_background);
            mCallButton.setImageResource(android.R.drawable.sym_action_call);
            mCallButton.setPadding(mCallButtonPadding, 0, mCallButtonPadding, 0);
            mCallButton.setScaleType(ScaleType.CENTER);
            addView(mCallButton);
        }

        mCallButton.setTag(tag);
        mCallButton.setVisibility(View.VISIBLE);
    }

    public void hideCallButton() {
        if (mCallButton != null) {
            mCallButton.setVisibility(View.GONE);
        }
    }

    /**
     * Adds or updates a text view for the phonetic name.
     */
    public void setPhoneticName(char[] text, int size) {
        if (text == null || size == 0) {
            if (mPhoneticNameTextView != null) {
                mPhoneticNameTextView.setVisibility(View.GONE);
            }
        } else {
            getPhoneticNameTextView();
            mPhoneticNameTextView.setText(text, 0, size);
            mPhoneticNameTextView.setVisibility(VISIBLE);
        }
    }

    /**
     * Returns the text view for the phonetic name, creating it if necessary.
     */
    public TextView getPhoneticNameTextView() {
        if (mPhoneticNameTextView == null) {
            mPhoneticNameTextView = new TextView(mContext);
            mPhoneticNameTextView.setSingleLine(true);
            mPhoneticNameTextView.setEllipsize(getTextEllipsis());
            mPhoneticNameTextView.setTextAppearance(mContext, android.R.style.TextAppearance_Small);
            mPhoneticNameTextView.setTypeface(mPhoneticNameTextView.getTypeface(), Typeface.BOLD);
            addView(mPhoneticNameTextView);
        }
        return mPhoneticNameTextView;
    }

    /**
     * Adds or updates a text view for the data label.
     */
    public void setLabel(CharSequence text) {
        if (TextUtils.isEmpty(text)) {
            if (mLabelView != null) {
                mLabelView.setVisibility(View.GONE);
            }
        } else {
            getLabelView();
            mLabelView.setText(text);
            mLabelView.setVisibility(VISIBLE);
        }
    }

    /**
     * Adds or updates a text view for the data label.
     */
    public void setLabel(char[] text, int size) {
        if (text == null || size == 0) {
            if (mLabelView != null) {
                mLabelView.setVisibility(View.GONE);
            }
        } else {
            getLabelView();
            mLabelView.setText(text, 0, size);
            mLabelView.setVisibility(VISIBLE);
        }
    }

    /**
     * Returns the text view for the data label, creating it if necessary.
     */
    public TextView getLabelView() {
        if (mLabelView == null) {
            mLabelView = new TextView(mContext);
            mLabelView.setSingleLine(true);
            mLabelView.setEllipsize(getTextEllipsis());
            mLabelView.setTextAppearance(mContext, android.R.style.TextAppearance_Small);
            mLabelView.setTypeface(mLabelView.getTypeface(), Typeface.BOLD);
            addView(mLabelView);
        }
        return mLabelView;
    }

    /**
     * Adds or updates a text view for the data element.
     */
    public void setData(char[] text, int size) {
        if (text == null || size == 0) {
            if (mDataView != null) {
                mDataView.setVisibility(View.GONE);
            }
            return;
        } else {
            getDataView();
            mDataView.setText(text, 0, size);
            mDataView.setVisibility(VISIBLE);
        }
    }

    /**
     * Returns the text view for the data text, creating it if necessary.
     */
    public TextView getDataView() {
        if (mDataView == null) {
            mDataView = new TextView(mContext);
            mDataView.setSingleLine(true);
            mDataView.setEllipsize(getTextEllipsis());
            mDataView.setTextAppearance(mContext, android.R.style.TextAppearance_Small);
            addView(mDataView);
        }
        return mDataView;
    }

    /**
     * Adds or updates a text view for the search snippet.
     */
    public void setSnippet(String text) {
        if (TextUtils.isEmpty(text)) {
            if (mSnippetView != null) {
                mSnippetView.setVisibility(View.GONE);
            }
        } else {
            mPrefixHighligher.setText(getSnippetView(), text, mHighlightedPrefix);
            mSnippetView.setVisibility(VISIBLE);
        }
    }

    /**
     * Returns the text view for the search snippet, creating it if necessary.
     */
    public TextView getSnippetView() {
        if (mSnippetView == null) {
            mSnippetView = new TextView(mContext);
            mSnippetView.setSingleLine(true);
            mSnippetView.setEllipsize(getTextEllipsis());
            mSnippetView.setTextAppearance(mContext, android.R.style.TextAppearance_Small);
            mSnippetView.setTypeface(mSnippetView.getTypeface(), Typeface.BOLD);
            addView(mSnippetView);
        }
        return mSnippetView;
    }

    /**
     * Returns the text view for the status, creating it if necessary.
     */

    public TextView getStatusView() {
        if (mStatusView == null) {
            mStatusView = new TextView(mContext);
            mStatusView.setSingleLine(true);
            mStatusView.setEllipsize(getTextEllipsis());
            mStatusView.setTextAppearance(mContext, android.R.style.TextAppearance_Small);
            mStatusView.setTextColor(Color.GRAY);
            addView(mStatusView);
        }
        return mStatusView;
    }

    /**
     * Returns the text view for the contacts count, creating it if necessary.
     */
    public TextView getCountView() {
        if (mCountView == null) {
            mCountView = new TextView(mContext);
            mCountView.setSingleLine(true);
            mCountView.setEllipsize(getTextEllipsis());
            mCountView.setTextAppearance(mContext, android.R.style.TextAppearance_Medium);
            mCountView.setTextColor(R.color.contact_count_text_color);
            addView(mCountView);
        }
        return mCountView;
    }

    /**
     * Adds or updates a text view for the contacts count.
     */
    public void setCountView(CharSequence text) {
        if (TextUtils.isEmpty(text)) {
            if (mCountView != null) {
                mCountView.setVisibility(View.GONE);
            }
        } else {
            getCountView();
            mCountView.setText(text);
            mCountView.setVisibility(VISIBLE);
        }
    }

    /**
     * Adds or updates a text view for the status.
     */
    public void setStatus(CharSequence text) {
        if (TextUtils.isEmpty(text)) {
            if (mStatusView != null) {
                mStatusView.setVisibility(View.GONE);
            }
        } else {
            getStatusView();
            mStatusView.setText(text);
            mStatusView.setVisibility(VISIBLE);
        }
    }

    /**
     * Adds or updates the presence icon view.
     */
    public void setPresence(Drawable icon) {
        if (icon != null) {
            if (mPresenceIcon == null) {
                mPresenceIcon = new ImageView(mContext);
                addView(mPresenceIcon);
            }
            mPresenceIcon.setImageDrawable(icon);
            mPresenceIcon.setScaleType(ScaleType.CENTER);
            mPresenceIcon.setVisibility(View.VISIBLE);
        } else {
            if (mPresenceIcon != null) {
                mPresenceIcon.setVisibility(View.GONE);
            }
        }
    }

    private TruncateAt getTextEllipsis() {
        return mActivatedStateSupported ? TruncateAt.START : TruncateAt.MARQUEE;
    }

    public void showDisplayName(Cursor cursor, int nameColumnIndex, int alternativeNameColumnIndex,
            boolean highlightingEnabled, int displayOrder) {
        // Copy out the display name and alternate display name.
        cursor.copyStringToBuffer(nameColumnIndex, mDisplayNameFormatter.getNameBuffer());
        cursor.copyStringToBuffer(alternativeNameColumnIndex,
                mDisplayNameFormatter.getAlternateNameBuffer());

        mDisplayNameFormatter.setDisplayName(
                getNameTextView(), displayOrder, highlightingEnabled, mHighlightedPrefix);
    }

    public void hideDisplayName() {
        if (mNameTextView != null) {
            removeView(mNameTextView);
            mNameTextView = null;
        }
    }

    public void showPhoneticName(Cursor cursor, int phoneticNameColumnIndex) {
        cursor.copyStringToBuffer(phoneticNameColumnIndex, mPhoneticNameBuffer);
        int phoneticNameSize = mPhoneticNameBuffer.sizeCopied;
        if (phoneticNameSize != 0) {
            setPhoneticName(mPhoneticNameBuffer.data, phoneticNameSize);
        } else {
            setPhoneticName(null, 0);
        }
    }

    public void hidePhoneticName() {
        if (mPhoneticNameTextView != null) {
            removeView(mPhoneticNameTextView);
            mPhoneticNameTextView = null;
        }
    }

    /**
     * Sets the proper icon (star or presence or nothing) and/or status message.
     */
    public void showPresenceAndStatusMessage(Cursor cursor, int presenceColumnIndex,
            int capabilityColumnIndex, int contactStatusColumnIndex) {
        Drawable icon = null;
        int presence = 0;
        int chatCapability = 0;
        if (!cursor.isNull(presenceColumnIndex)) {
            presence = cursor.getInt(presenceColumnIndex);
            if (capabilityColumnIndex != 0 && !cursor.isNull(presenceColumnIndex)) {
                chatCapability = cursor.getInt(capabilityColumnIndex);
            }
            icon = ContactPresenceIconUtil.getChatCapabilityIcon(
                    getContext(), presence, chatCapability);
        }
        setPresence(icon);

        String statusMessage = null;
        if (contactStatusColumnIndex != 0 && !cursor.isNull(contactStatusColumnIndex)) {
            statusMessage = cursor.getString(contactStatusColumnIndex);
        }
        // If there is no status message from the contact, but there was a presence value, then use
        // the default status message string
        if (statusMessage == null && presence != 0) {
            statusMessage = ContactStatusUtil.getStatusString(getContext(), presence);
        }
        setStatus(statusMessage);
    }

    /**
     * Shows search snippet.
     */
    public void showSnippet(Cursor cursor, int summarySnippetColumnIndex) {
        if (cursor.getColumnCount() <= summarySnippetColumnIndex) {
            setSnippet(null);
            return;
        }

        String snippet = cursor.getString(summarySnippetColumnIndex);
        if (snippet != null) {
            int from = 0;
            int to = snippet.length();
            int start = snippet.indexOf(DefaultContactListAdapter.SNIPPET_START_MATCH);
            if (start == -1) {
                snippet = null;
            } else {
                int firstNl = snippet.lastIndexOf('\n', start);
                if (firstNl != -1) {
                    from = firstNl + 1;
                }
                int end = snippet.lastIndexOf(DefaultContactListAdapter.SNIPPET_END_MATCH);
                if (end != -1) {
                    int lastNl = snippet.indexOf('\n', end);
                    if (lastNl != -1) {
                        to = lastNl;
                    }
                }

                StringBuilder sb = new StringBuilder();
                for (int i = from; i < to; i++) {
                    char c = snippet.charAt(i);
                    if (c != DefaultContactListAdapter.SNIPPET_START_MATCH &&
                            c != DefaultContactListAdapter.SNIPPET_END_MATCH) {
                        sb.append(c);
                    }
                }
                snippet = sb.toString();
            }
        }
        setSnippet(snippet);
    }

    /**
     * Shows data element (e.g. phone number).
     */
    public void showData(Cursor cursor, int dataColumnIndex) {
        cursor.copyStringToBuffer(dataColumnIndex, mDataBuffer);
        setData(mDataBuffer.data, mDataBuffer.sizeCopied);
    }

    public void setActivatedStateSupported(boolean flag) {
        this.mActivatedStateSupported = flag;
    }

    @Override
    public void requestLayout() {
        // We will assume that once measured this will not need to resize
        // itself, so there is no need to pass the layout request to the parent
        // view (ListView).
        forceLayout();
    }
}
