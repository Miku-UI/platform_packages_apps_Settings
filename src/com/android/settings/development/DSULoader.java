/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.settings.development;

import android.app.Activity;
import android.app.ListActivity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemProperties;
import android.os.image.DynamicSystemClient;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Slog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import com.android.settings.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;

/**
 * DSU Loader is a front-end that offers developers the ability to boot into GSI with one-click. It
 * also offers the flexibility to overwrite the default setting and load OEMs owned images.
 */
public class DSULoader extends ListActivity {
    private static final int Q_VNDK_BASE = 28;
    private static final int Q_OS_BASE = 10;

    private static final boolean DEBUG = false;
    private static final String TAG = "DSULOADER";
    private static final String PROPERTY_KEY_CPU = "ro.product.cpu.abi";
    private static final String PROPERTY_KEY_OS = "ro.system.build.version.release";
    private static final String PROPERTY_KEY_VNDK = "ro.vndk.version";
    private static final String PROPERTY_KEY_LIST =
            "persist.sys.fflag.override.settings_dynamic_system.list";
    private static final String PROPERTY_KEY_SPL = "ro.build.version.security_patch";
    private static final String DSU_LIST =
            "https://dl.google.com/developers/android/gsi/gsi-src.json";

    private static final int TIMEOUT_MS = 10 * 1000;
    private static final int REQUEST_PICK_GSI = 1;
    private static final String DOCUMENTS_UI_PACKAGE = "com.android.documentsui";
    private static final int IMAGE_INVALID = 0;
    private static final int IMAGE_SPARSE = 1;
    private static final int IMAGE_RAW = 2;
    private static final int SPARSE_MAGIC = 0xed26ff3a;
    private static final int SPARSE_HEADER_SIZE = 28;
    private static final int SPARSE_CHUNK_HEADER_SIZE = 12;
    // ext4 superblock starts at 1024; s_magic is 0x38 bytes into it.
    private static final int EXT4_MAGIC_OFFSET = 0x438;
    private static final int EXT4_SUPER_MAGIC = 0xEF53;
    private static final int FS_SUPER_OFFSET = 1024;
    private static final int EROFS_SUPER_MAGIC = 0xE0F5E1E2;
    private static final int F2FS_SUPER_MAGIC = 0xF2F52010;
    private static final int HEADER_BYTES = 4096;
    private List<Object> mDSUList = new ArrayList<Object>();
    private ArrayAdapter<Object> mAdapter;

    private static String readAll(InputStream in) throws IOException {
        int n;
        StringBuilder list = new StringBuilder();
        byte[] bytes = new byte[4096];
        while ((n = in.read(bytes, 0, 4096)) != -1) {
            list.append(new String(Arrays.copyOf(bytes, n)));
        }
        return list.toString();
    }

    private static String readAll(URL url) throws IOException {
        InputStream in = null;
        HttpsURLConnection connection = null;
        Slog.i(TAG, "fetch " + url.toString());
        try {
            connection = (HttpsURLConnection) url.openConnection();
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setDoInput(true);
            connection.connect();
            int responseCode = connection.getResponseCode();
            if (connection.getResponseCode() != HttpsURLConnection.HTTP_OK) {
                throw new IOException("HTTP error code: " + responseCode);
            }
            in = new BufferedInputStream(connection.getInputStream());
            return readAll(in);
        } catch (Exception e) {
            throw e;
        } finally {
            try {
                if (in != null) {
                    in.close();
                    in = null;
                }
            } catch (IOException e) {
                // ignore
            }
            if (connection != null) {
                connection.disconnect();
                connection = null;
            }
        }
    }

    private void resizeListView() {
        ListView view = getListView();
        View item_view = mAdapter.getView(0, null, view);
        item_view.measure(
                MeasureSpec.makeMeasureSpec(view.getWidth(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        ViewGroup.LayoutParams params = view.getLayoutParams();
        params.height = item_view.getMeasuredHeight() * (mAdapter.getCount() + 1);
        Slog.e(TAG, "resizeListView height=" + params.height);
        view.setLayoutParams(params);
        view.requestLayout();
    }

    // Fetcher fetches mDSUList in backgroud
    private class Fetcher implements Runnable {
        private URL mDsuList;

        Fetcher(URL dsuList) {
            mDsuList = dsuList;
        }

        private void fetch(URL url)
                throws IOException, JSONException, MalformedURLException, ParseException {
            String content = readAll(url);
            JSONObject jsn = new JSONObject(content);
            // The include primitive is like below
            // "include": [
            //   "https:/...json",
            //    ...
            // ]
            if (jsn.has("include")) {
                JSONArray include = jsn.getJSONArray("include");
                int len = include.length();
                for (int i = 0; i < len; i++) {
                    if (include.isNull(i)) {
                        continue;
                    }
                    fetch(new URL(include.getString(i)));
                }
            }
            //  "images":[
            //    {
            //      "name":"...",
            //      "os_version":"10",
            //      "cpu_abi":"...",
            //      "details":"...",
            //      "vndk":[],
            //      "spl":"...",
            //      "pubkey":"",
            //      "uri":"https://...zip"
            //    },
            //     ...
            //  ]
            if (jsn.has("images")) {
                JSONArray images = jsn.getJSONArray("images");
                int len = images.length();
                for (int i = 0; i < len; i++) {
                    DSUPackage dsu = new DSUPackage(images.getJSONObject(i));
                    if (dsu.isSupported()) {
                        mDSUList.add(dsu);
                    }
                }
            }
        }

        public void run() {
            try {
                fetch(mDsuList);
                if (mDSUList.size() == 0) {
                    mDSUList.add(0, "No DSU available for this device");
                }
            } catch (IOException e) {
                Slog.e(TAG, e.toString());
                mDSUList.add(0, "Network Error");
            } catch (Exception e) {
                Slog.e(TAG, e.toString());
                mDSUList.add(0, "Metadata Error");
            }
            runOnUiThread(
                    new Runnable() {
                        public void run() {
                            mAdapter.clear();
                            mAdapter.addAll(mDSUList);
                            resizeListView();
                        }
                    });
        }
    }

    private class DSUPackage {
        private static final String NAME = "name";
        private static final String DETAILS = "details";
        private static final String CPU_ABI = "cpu_abi";
        private static final String URI = "uri";
        private static final String OS_VERSION = "os_version";
        private static final String VNDK = "vndk";
        private static final String PUBKEY = "pubkey";
        private static final String SPL = "spl";
        private static final String SPL_FORMAT = "yyyy-MM-dd";
        private static final String TOS = "tos";

        String mName = null;
        String mDetails = null;
        String mCpuAbi = null;
        int mOsVersion = -1;
        int[] mVndk = null;
        String mPubKey = "";
        Date mSPL = null;
        URL mTosUrl = null;
        URL mUri;

        DSUPackage(JSONObject jsn) throws JSONException, MalformedURLException, ParseException {
            Slog.i(TAG, "DSUPackage: " + jsn.toString());
            mName = jsn.getString(NAME);
            mDetails = jsn.getString(DETAILS);
            mCpuAbi = jsn.getString(CPU_ABI);
            mUri = new URL(jsn.getString(URI));
            if (jsn.has(OS_VERSION)) {
                mOsVersion = dessertNumber(jsn.getString(OS_VERSION), Q_OS_BASE);
            }
            if (jsn.has(VNDK)) {
                JSONArray vndks = jsn.getJSONArray(VNDK);
                mVndk = new int[vndks.length()];
                for (int i = 0; i < vndks.length(); i++) {
                    mVndk[i] = vndks.getInt(i);
                }
            }
            if (jsn.has(PUBKEY)) {
                mPubKey = jsn.getString(PUBKEY);
            }
            if (jsn.has(TOS)) {
                mTosUrl = new URL(jsn.getString(TOS));
            }
            if (jsn.has(SPL)) {
                mSPL = new SimpleDateFormat(SPL_FORMAT).parse(jsn.getString(SPL));
            }
        }

        int dessertNumber(String s, int base) {
            if (s == null || s.isEmpty()) {
                return -1;
            }
            if (Character.isDigit(s.charAt(0))) {
                return Integer.parseInt(s);
            } else {
                s = s.toUpperCase();
                return ((int) s.charAt(0) - (int) 'Q') + base;
            }
        }

        int getDeviceVndk() {
            if (DEBUG) {
                return Q_VNDK_BASE;
            }
            return dessertNumber(SystemProperties.get(PROPERTY_KEY_VNDK), Q_VNDK_BASE);
        }

        int getDeviceOs() {
            if (DEBUG) {
                return Q_OS_BASE;
            }
            return dessertNumber(SystemProperties.get(PROPERTY_KEY_OS), Q_OS_BASE);
        }

        String getDeviceCpu() {
            String cpu = SystemProperties.get(PROPERTY_KEY_CPU);
            cpu = cpu.toLowerCase();
            if (cpu.startsWith("aarch64")) {
                cpu = "arm64-v8a";
            }
            return cpu;
        }

        Date getDeviceSPL() {
            String spl = SystemProperties.get(PROPERTY_KEY_SPL);
            if (TextUtils.isEmpty(spl)) {
                return null;
            }
            try {
                return new SimpleDateFormat(SPL_FORMAT).parse(spl);
            } catch (ParseException e) {
                return null;
            }
        }

        boolean isSupported() {
            boolean supported = true;
            String cpu = getDeviceCpu();
            if (!mCpuAbi.equals(cpu)) {
                Slog.i(TAG, mCpuAbi + " != " + cpu);
                supported = false;
            }
            if (mOsVersion > 0) {
                int os = getDeviceOs();
                if (os < 0) {
                    Slog.i(TAG, "Failed to getDeviceOs");
                    supported = false;
                } else if (mOsVersion < os) {
                    Slog.i(TAG, mOsVersion + " < " + os);
                    supported = false;
                }
            }
            if (mVndk != null) {
                int vndk = getDeviceVndk();
                if (vndk < 0) {
                    Slog.i(TAG, "Failed to getDeviceVndk");
                    supported = false;
                } else {
                    boolean found_vndk = false;
                    for (int i = 0; i < mVndk.length; i++) {
                        if (mVndk[i] == vndk) {
                            found_vndk = true;
                            break;
                        }
                    }
                    if (!found_vndk) {
                        Slog.i(TAG, "vndk:" + vndk + " not found");
                        supported = false;
                    }
                }
            }
            if (mSPL != null) {
                Date spl = getDeviceSPL();
                if (spl == null) {
                    Slog.i(TAG, "Failed to getDeviceSPL");
                    supported = false;
                } else if (spl.getTime() > mSPL.getTime()) {
                    Slog.i(TAG, "Device SPL:" + spl.toString() + " > " + mSPL.toString());
                    supported = false;
                }
            }
            Slog.i(TAG, mName + " isSupported " + supported);
            return supported;
        }
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.dsu_loader);
        findViewById(R.id.dsu_install_local_gsi).setOnClickListener(v -> pickLocalGsi());
        String dsuList = SystemProperties.get(PROPERTY_KEY_LIST);
        Slog.e(TAG, "Try to get DSU list from: " + PROPERTY_KEY_LIST);
        if (dsuList == null || dsuList.isEmpty()) {
            dsuList = DSU_LIST;
        }
        Slog.e(TAG, "DSU list: " + dsuList);
        URL url = null;
        try {
            url = new URL(dsuList);
        } catch (MalformedURLException e) {
            Slog.e(TAG, e.toString());
            return;
        }
        mAdapter = new DSUPackageListAdapter(this);
        setListAdapter(mAdapter);
        mAdapter.add(getResources().getString(R.string.dsu_loader_loading));
        new Thread(new Fetcher(url)).start();
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        Object selected = mAdapter.getItem(position);
        if (selected instanceof DSUPackage) {
            DSUPackage dsu = (DSUPackage) selected;
            mAdapter.clear();
            mAdapter.add(getResources().getString(R.string.dsu_loader_loading));
            new Thread(new Runnable() {
                public void run() {
                    String termsOfService = "";
                    if (dsu.mTosUrl != null) {
                        try {
                            termsOfService = readAll(dsu.mTosUrl);
                        } catch (IOException e) {
                            Slog.e(TAG, e.toString());
                        }
                    }
                    Intent intent = new Intent(DSULoader.this, DSUTermsOfServiceActivity.class);
                    intent.putExtra(DSUTermsOfServiceActivity.KEY_TOS, termsOfService);
                    intent.setData(Uri.parse(dsu.mUri.toString()));
                    intent.putExtra("KEY_PUBKEY", dsu.mPubKey);
                    startActivity(intent);
                }
            }).start();
        }
        finish();
    }

    private void pickLocalGsi() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.setPackage(DOCUMENTS_UI_PACKAGE);
        try {
            startActivityForResult(intent, REQUEST_PICK_GSI);
        } catch (ActivityNotFoundException e) {
            intent.setPackage(null);
            try {
                startActivityForResult(intent, REQUEST_PICK_GSI);
            } catch (ActivityNotFoundException e2) {
                Slog.e(TAG, "No file manager", e2);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_PICK_GSI) {
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        int grantFlags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (grantFlags != 0) {
            try {
                getContentResolver().takePersistableUriPermission(uri, grantFlags);
            } catch (SecurityException e) {
                Slog.i(TAG, "persistable permission not available");
            }
        }

        String name = null;
        long size = -1;
        try (Cursor cursor = getContentResolver().query(uri,
                new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                name = cursor.getString(0);
                if (!cursor.isNull(1)) {
                    size = cursor.getLong(1);
                }
            }
        } catch (SecurityException e) {
            Slog.e(TAG, "query failed", e);
            showInvalidImage();
            return;
        }
        if (name == null && uri.getLastPathSegment() != null) {
            name = Uri.decode(uri.getLastPathSegment());
        }
        if (name == null || !name.toLowerCase(Locale.US).endsWith(".img")) {
            showInvalidImage();
            return;
        }

        int kind;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                showInvalidImage();
                return;
            }
            kind = inspectSystemImage(in);
        } catch (IOException | SecurityException e) {
            Slog.e(TAG, "read image header failed", e);
            showInvalidImage();
            return;
        }
        if (kind == IMAGE_INVALID) {
            showInvalidImage();
            return;
        }
        if (size <= 0) {
            try (AssetFileDescriptor afd = getContentResolver().openAssetFileDescriptor(uri, "r")) {
                if (afd != null) {
                    size = afd.getLength();
                }
            } catch (IOException e) {
                size = -1;
            }
        }
        if (kind == IMAGE_RAW && size <= 0) {
            showInvalidImage();
            return;
        }
        installLocalGsi(uri, size);
    }

    private void installLocalGsi(Uri uri, long size) {
        try {
            grantUriPermission("com.android.dynsystem", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            Slog.e(TAG, "grantUriPermission failed", e);
            showInvalidImage();
            return;
        }
        Intent intent = new Intent();
        intent.setClassName("com.android.dynsystem", "com.android.dynsystem.VerificationActivity");
        intent.setAction(DynamicSystemClient.ACTION_START_INSTALL);
        intent.setData(uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (size > 0) {
            intent.putExtra(DynamicSystemClient.KEY_SYSTEM_SIZE, size);
        }
        intent.putExtra(DynamicSystemClient.KEY_SYSTEM_IMAGE, true);
        intent.putExtra("KEY_PUBKEY", "");
        startActivity(intent);
        finish();
    }

    private void showInvalidImage() {
        Toast.makeText(this, R.string.dsu_local_gsi_invalid, Toast.LENGTH_LONG).show();
    }

    /** Sparse system.img, or a raw ext4 / erofs / f2fs image. */
    private static int inspectSystemImage(InputStream in) throws IOException {
        byte[] buf = new byte[HEADER_BYTES];
        int n = 0;
        while (n < buf.length) {
            int read = in.read(buf, n, buf.length - n);
            if (read < 0) {
                break;
            }
            n += read;
        }
        if (n >= 8 && isBootImage(buf)) {
            return IMAGE_INVALID;
        }
        if (n >= SPARSE_HEADER_SIZE && isSparseImage(buf, n)) {
            return IMAGE_SPARSE;
        }
        ByteBuffer little = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN);
        if (n >= EXT4_MAGIC_OFFSET + 2
                && little.getShort(EXT4_MAGIC_OFFSET) == (short) EXT4_SUPER_MAGIC) {
            return IMAGE_RAW;
        }
        if (n >= FS_SUPER_OFFSET + 4) {
            int magic = little.getInt(FS_SUPER_OFFSET);
            if (magic == EROFS_SUPER_MAGIC || magic == F2FS_SUPER_MAGIC) {
                return IMAGE_RAW;
            }
        }
        return IMAGE_INVALID;
    }

    private static boolean isBootImage(byte[] buf) {
        return buf[0] == 'A' && buf[1] == 'N' && buf[2] == 'D' && buf[3] == 'R'
                && buf[4] == 'O' && buf[5] == 'I' && buf[6] == 'D' && buf[7] == '!';
    }

    private static boolean isSparseImage(byte[] buf, int length) {
        ByteBuffer header = ByteBuffer.wrap(buf, 0, length).order(ByteOrder.LITTLE_ENDIAN);
        if (header.getInt(0) != SPARSE_MAGIC) {
            return false;
        }
        int major = header.getShort(4) & 0xffff;
        int minor = header.getShort(6) & 0xffff;
        int fileHeader = header.getShort(8) & 0xffff;
        int chunkHeader = header.getShort(10) & 0xffff;
        int blockSize = header.getInt(12);
        int totalBlocks = header.getInt(16);
        return major <= 1
                && minor == 0
                && fileHeader == SPARSE_HEADER_SIZE
                && chunkHeader == SPARSE_CHUNK_HEADER_SIZE
                && blockSize >= 512
                && (blockSize & 0x3) == 0
                && totalBlocks > 0;
    }

    private class DSUPackageListAdapter extends ArrayAdapter<Object> {
        private final LayoutInflater mInflater;

        DSUPackageListAdapter(Context context) {
            super(context, 0);
            mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            AppViewHolder holder = AppViewHolder.createOrRecycle(mInflater, convertView);
            convertView = holder.rootView;
            Object item = getItem(position);
            if (item instanceof DSUPackage) {
                DSUPackage dsu = (DSUPackage) item;
                holder.appName.setText(dsu.mName);
                holder.summary.setText(dsu.mDetails);
            } else {
                String msg = (String) item;
                holder.summary.setText(msg);
            }
            holder.appIcon.setImageDrawable(null);
            holder.disabled.setVisibility(View.GONE);
            return convertView;
        }
    }
}
