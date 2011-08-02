/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.contacts.model;

import com.google.common.annotations.VisibleForTesting;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A general contacts account type descriptor.
 */
public class ExternalAccountType extends BaseAccountType {
    private static final String TAG = "ExternalAccountType";

    private static final String ACTION_SYNC_ADAPTER = "android.content.SyncAdapter";
    private static final String METADATA_CONTACTS = "android.provider.CONTACTS_STRUCTURE";

    private static final String TAG_CONTACTS_SOURCE_LEGACY = "ContactsSource";
    private static final String TAG_CONTACTS_ACCOUNT_TYPE = "ContactsAccountType";
    private static final String TAG_CONTACTS_DATA_KIND = "ContactsDataKind";

    private static final String ATTR_EDIT_CONTACT_ACTIVITY = "editContactActivity";
    private static final String ATTR_CREATE_CONTACT_ACTIVITY = "createContactActivity";
    private static final String ATTR_INVITE_CONTACT_ACTIVITY = "inviteContactActivity";
    private static final String ATTR_INVITE_CONTACT_ACTION_LABEL = "inviteContactActionLabel";
    private static final String ATTR_DATA_SET = "dataSet";
    private static final String ATTR_EXTENSION_PACKAGE_NAMES = "extensionPackageNames";

    // The following attributes should only be set in non-sync-adapter account types.  They allow
    // for the account type and resource IDs to be specified without an associated authenticator.
    private static final String ATTR_ACCOUNT_TYPE = "accountType";
    private static final String ATTR_READ_ONLY = "readOnly";
    private static final String ATTR_ACCOUNT_LABEL = "accountTypeLabel";
    private static final String ATTR_ACCOUNT_ICON = "accountTypeIcon";

    private String mEditContactActivityClassName;
    private String mCreateContactActivityClassName;
    private String mInviteContactActivity;
    private String mInviteActionLabelAttribute;
    private List<String> mExtensionPackageNames;
    private int mInviteActionLabelResId;
    private String mAccountTypeLabelAttribute;
    private String mAccountTypeIconAttribute;
    private boolean mInitSuccessful;

    public ExternalAccountType(Context context, String resPackageName) {
        this.resPackageName = resPackageName;
        this.summaryResPackageName = resPackageName;

        // Handle unknown sources by searching their package
        final PackageManager pm = context.getPackageManager();
        try {
            PackageInfo packageInfo = pm.getPackageInfo(resPackageName,
                    PackageManager.GET_SERVICES|PackageManager.GET_META_DATA);
            for (ServiceInfo serviceInfo : packageInfo.services) {
                final XmlResourceParser parser = serviceInfo.loadXmlMetaData(pm,
                        METADATA_CONTACTS);
                if (parser == null) continue;
                inflate(context, parser);
            }
        } catch (NameNotFoundException nnfe) {
            // If the package name is not found, we can't initialize this account type.
            return;
        }

        mExtensionPackageNames = new ArrayList<String>();
        mInviteActionLabelResId = resolveExternalResId(context, mInviteActionLabelAttribute,
                summaryResPackageName, ATTR_INVITE_CONTACT_ACTION_LABEL);
        titleRes = resolveExternalResId(context, mAccountTypeLabelAttribute,
                this.resPackageName, ATTR_ACCOUNT_LABEL);
        iconRes = resolveExternalResId(context, mAccountTypeIconAttribute,
                this.resPackageName, ATTR_ACCOUNT_ICON);

        // Bring in name and photo from fallback source, which are non-optional
        addDataKindStructuredName(context);
        addDataKindDisplayName(context);
        addDataKindPhoneticName(context);
        addDataKindPhoto(context);

        // If we reach this point, the account type has been successfully initialized.
        mInitSuccessful = true;
    }

    @Override
    public boolean isExternal() {
        return true;
    }

    /**
     * Whether this account type was able to be fully initialized.  This may be false if
     * (for example) the package name associated with the account type could not be found.
     */
    public boolean isInitialized() {
        return mInitSuccessful;
    }

    @Override
    public String getEditContactActivityClassName() {
        return mEditContactActivityClassName;
    }

    @Override
    public String getCreateContactActivityClassName() {
        return mCreateContactActivityClassName;
    }

    @Override
    public String getInviteContactActivityClassName() {
        return mInviteContactActivity;
    }

    @Override
    protected int getInviteContactActionResId(Context context) {
        return mInviteActionLabelResId;
    }

    @Override
    public List<String> getExtensionPackageNames() {
        return mExtensionPackageNames;
    }

    /**
     * Inflate this {@link AccountType} from the given parser. This may only
     * load details matching the publicly-defined schema.
     */
    protected void inflate(Context context, XmlPullParser parser) {
        final AttributeSet attrs = Xml.asAttributeSet(parser);

        try {
            int type;
            while ((type = parser.next()) != XmlPullParser.START_TAG
                    && type != XmlPullParser.END_DOCUMENT) {
                // Drain comments and whitespace
            }

            if (type != XmlPullParser.START_TAG) {
                throw new IllegalStateException("No start tag found");
            }

            String rootTag = parser.getName();
            if (!TAG_CONTACTS_ACCOUNT_TYPE.equals(rootTag) &&
                    !TAG_CONTACTS_SOURCE_LEGACY.equals(rootTag)) {
                throw new IllegalStateException("Top level element must be "
                        + TAG_CONTACTS_ACCOUNT_TYPE + ", not " + rootTag);
            }

            int attributeCount = parser.getAttributeCount();
            for (int i = 0; i < attributeCount; i++) {
                String attr = parser.getAttributeName(i);
                String value = parser.getAttributeValue(i);
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, attr + "=" + value);
                }
                if (ATTR_EDIT_CONTACT_ACTIVITY.equals(attr)) {
                    mEditContactActivityClassName = value;
                } else if (ATTR_CREATE_CONTACT_ACTIVITY.equals(attr)) {
                    mCreateContactActivityClassName = value;
                } else if (ATTR_INVITE_CONTACT_ACTIVITY.equals(attr)) {
                    mInviteContactActivity = value;
                } else if (ATTR_INVITE_CONTACT_ACTION_LABEL.equals(attr)) {
                    mInviteActionLabelAttribute = value;
                } else if (ATTR_DATA_SET.equals(attr)) {
                    dataSet = value;
                } else if (ATTR_EXTENSION_PACKAGE_NAMES.equals(attr)) {
                    mExtensionPackageNames.add(value);
                } else if (ATTR_ACCOUNT_TYPE.equals(attr)) {
                    accountType = value;
                } else if (ATTR_READ_ONLY.equals(attr)) {
                    readOnly = !"0".equals(value) && !"false".equals(value);
                } else if (ATTR_ACCOUNT_LABEL.equals(attr)) {
                    mAccountTypeLabelAttribute = value;
                } else if (ATTR_ACCOUNT_ICON.equals(attr)) {
                    mAccountTypeIconAttribute = value;
                } else {
                    Log.e(TAG, "Unsupported attribute " + attr);
                }
            }

            // Parse all children kinds
            final int depth = parser.getDepth();
            while (((type = parser.next()) != XmlPullParser.END_TAG || parser.getDepth() > depth)
                    && type != XmlPullParser.END_DOCUMENT) {
                String tag = parser.getName();
                if (type == XmlPullParser.END_TAG || !TAG_CONTACTS_DATA_KIND.equals(tag)) {
                    continue;
                }

                final TypedArray a = context.obtainStyledAttributes(attrs,
                        android.R.styleable.ContactsDataKind);
                final DataKind kind = new DataKind();

                kind.mimeType = a
                        .getString(com.android.internal.R.styleable.ContactsDataKind_mimeType);
                kind.iconRes = a.getResourceId(
                        com.android.internal.R.styleable.ContactsDataKind_icon, -1);

                final String summaryColumn = a
                        .getString(com.android.internal.R.styleable.ContactsDataKind_summaryColumn);
                if (summaryColumn != null) {
                    // Inflate a specific column as summary when requested
                    kind.actionHeader = new FallbackAccountType.SimpleInflater(summaryColumn);
                }

                final String detailColumn = a
                        .getString(com.android.internal.R.styleable.ContactsDataKind_detailColumn);
                final boolean detailSocialSummary = a.getBoolean(
                        com.android.internal.R.styleable.ContactsDataKind_detailSocialSummary,
                        false);

                if (detailSocialSummary) {
                    // Inflate social summary when requested
                    kind.actionBodySocial = true;
                }

                if (detailColumn != null) {
                    // Inflate specific column as summary
                    kind.actionBody = new FallbackAccountType.SimpleInflater(detailColumn);
                }

                addKind(kind);
                a.recycle();
            }
        } catch (XmlPullParserException e) {
            throw new IllegalStateException("Problem reading XML", e);
        } catch (IOException e) {
            throw new IllegalStateException("Problem reading XML", e);
        }
    }

    @Override
    public int getHeaderColor(Context context) {
        return 0xff6d86b4;
    }

    @Override
    public int getSideBarColor(Context context) {
        return 0xff6d86b4;
    }

    /**
     * Takes a string in the "@xxx/yyy" format and return the resource ID for the resource in
     * the resource package.
     *
     * If the argument is in the invalid format or isn't a resource name, it returns -1.
     *
     * @param context context
     * @param resourceName Resource name in the "@xxx/yyy" format, e.g. "@string/invite_lavbel"
     * @param packageName name of the package containing the resource.
     * @param xmlAttributeName attribute name which the resource came from.  Used for logging.
     */
    @VisibleForTesting
    static int resolveExternalResId(Context context, String resourceName,
            String packageName, String xmlAttributeName) {
        if (TextUtils.isEmpty(resourceName)) {
            return -1; // Empty text is okay.
        }
        if (resourceName.charAt(0) != '@') {
            Log.e(TAG, xmlAttributeName + " must be a resource name beginnig with '@'");
            return -1;
        }
        final String name = resourceName.substring(1);
        final Resources res;
        try {
             res = context.getPackageManager().getResourcesForApplication(packageName);
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Unable to load package " + packageName);
            return -1;
        }
        final int resId = res.getIdentifier(name, null, packageName);
        if (resId == 0) {
            Log.e(TAG, "Unable to load " + resourceName + " from package " + packageName);
            return -1;
        }
        return resId;
    }
}
