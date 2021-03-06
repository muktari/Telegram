/*
 * This is the source code of Telegram for Android v. 1.3.2.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013.
 */

package org.telegram.messenger;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.provider.MediaStore;

import org.telegram.android.AndroidUtilities;
import org.telegram.ui.ApplicationLoader;

import java.io.RandomAccessFile;
import java.net.URL;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URLConnection;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Scanner;

public class FileLoadOperation {

    private static class RequestInfo {
        private long requestToken = 0;
        private int offset = 0;
        private TLRPC.TL_upload_file response = null;
    }

    private final static int downloadChunkSize = 1024 * 32;
    private final static int maxDownloadRequests = 3;

    public int datacenter_id;
    public TLRPC.InputFileLocation location;
    public volatile int state = 0;
    private int downloadedBytes;
    public int totalBytesCount;
    public FileLoadOperationDelegate delegate;
    public Bitmap image;
    public String filter;
    private byte[] key;
    private byte[] iv;

    private int nextDownloadOffset = 0;
    private ArrayList<RequestInfo> requestInfos = new ArrayList<RequestInfo>(maxDownloadRequests);
    private ArrayList<RequestInfo> delayedRequestInfos = new ArrayList<RequestInfo>(maxDownloadRequests - 1);

    private File cacheFileTemp;
    private File cacheFileFinal;
    private File cacheIvTemp;

    private String ext;
    private String httpUrl;
    private URLConnection httpConnection;
    public boolean needBitmapCreate = true;
    private InputStream httpConnectionStream;
    private RandomAccessFile fileOutputStream;
    private RandomAccessFile fiv;

    public static interface FileLoadOperationDelegate {
        public abstract void didFinishLoadingFile(FileLoadOperation operation);
        public abstract void didFailedLoadingFile(FileLoadOperation operation);
        public abstract void didChangedLoadProgress(FileLoadOperation operation, float progress);
    }

    public FileLoadOperation(TLRPC.FileLocation fileLocation) {
        if (fileLocation instanceof TLRPC.TL_fileEncryptedLocation) {
            location = new TLRPC.TL_inputEncryptedFileLocation();
            location.id = fileLocation.volume_id;
            location.volume_id = fileLocation.volume_id;
            location.access_hash = fileLocation.secret;
            location.local_id = fileLocation.local_id;
            iv = new byte[32];
            System.arraycopy(fileLocation.iv, 0, iv, 0, iv.length);
            key = fileLocation.key;
            datacenter_id = fileLocation.dc_id;
        } else if (fileLocation instanceof TLRPC.TL_fileLocation) {
            location = new TLRPC.TL_inputFileLocation();
            location.volume_id = fileLocation.volume_id;
            location.secret = fileLocation.secret;
            location.local_id = fileLocation.local_id;
            datacenter_id = fileLocation.dc_id;
        }
    }

    public FileLoadOperation(TLRPC.Video videoLocation) {
        if (videoLocation instanceof TLRPC.TL_videoEncrypted) {
            location = new TLRPC.TL_inputEncryptedFileLocation();
            location.id = videoLocation.id;
            location.access_hash = videoLocation.access_hash;
            datacenter_id = videoLocation.dc_id;
            iv = new byte[32];
            System.arraycopy(videoLocation.iv, 0, iv, 0, iv.length);
            key = videoLocation.key;
        } else if (videoLocation instanceof TLRPC.TL_video) {
            location = new TLRPC.TL_inputVideoFileLocation();
            datacenter_id = videoLocation.dc_id;
            location.id = videoLocation.id;
            location.access_hash = videoLocation.access_hash;
        }
        ext = ".mp4";
    }

    public FileLoadOperation(TLRPC.Audio audioLocation) {
        if (audioLocation instanceof TLRPC.TL_audioEncrypted) {
            location = new TLRPC.TL_inputEncryptedFileLocation();
            location.id = audioLocation.id;
            location.access_hash = audioLocation.access_hash;
            datacenter_id = audioLocation.dc_id;
            iv = new byte[32];
            System.arraycopy(audioLocation.iv, 0, iv, 0, iv.length);
            key = audioLocation.key;
        } else if (audioLocation instanceof TLRPC.TL_audio) {
            location = new TLRPC.TL_inputAudioFileLocation();
            datacenter_id = audioLocation.dc_id;
            location.id = audioLocation.id;
            location.access_hash = audioLocation.access_hash;
        }
        ext = ".m4a";
    }

    public FileLoadOperation(TLRPC.Document documentLocation) {
        if (documentLocation instanceof TLRPC.TL_documentEncrypted) {
            location = new TLRPC.TL_inputEncryptedFileLocation();
            location.id = documentLocation.id;
            location.access_hash = documentLocation.access_hash;
            datacenter_id = documentLocation.dc_id;
            iv = new byte[32];
            System.arraycopy(documentLocation.iv, 0, iv, 0, iv.length);
            key = documentLocation.key;
        } else if (documentLocation instanceof TLRPC.TL_document) {
            location = new TLRPC.TL_inputDocumentFileLocation();
            datacenter_id = documentLocation.dc_id;
            location.id = documentLocation.id;
            location.access_hash = documentLocation.access_hash;
        }
        ext = documentLocation.file_name;
        int idx = -1;
        if (ext == null || (idx = ext.lastIndexOf(".")) == -1) {
            ext = "";
        } else {
            ext = ext.substring(idx);
            if (ext.length() <= 1) {
                ext = "";
            }
        }
    }

    public FileLoadOperation(String url) {
        httpUrl = url;
    }

    public void start() {
        if (state != 0) {
            return;
        }
        state = 1;
        if (location == null && httpUrl == null) {
            Utilities.stageQueue.postRunnable(new Runnable() {
                @Override
                public void run() {
                    delegate.didFailedLoadingFile(FileLoadOperation.this);
                }
            });
            return;
        }
        boolean ignoreCache = false;
        boolean onlyCache = false;
        boolean isLocalFile = false;
        Long mediaId = null;
        String fileNameFinal = null;
        String fileNameTemp = null;
        String fileNameIv = null;
        if (httpUrl != null) {
            if (!httpUrl.startsWith("http")) {
                if (httpUrl.startsWith("thumb://")) {
                    int idx = httpUrl.indexOf(":", 8);
                    if (idx >= 0) {
                        String media = httpUrl.substring(8, idx);
                        mediaId = Long.parseLong(media);
                        fileNameFinal = httpUrl.substring(idx + 1);
                    }
                } else {
                    fileNameFinal = httpUrl;
                }
                onlyCache = true;
                isLocalFile = true;
            } else {
                fileNameFinal = Utilities.MD5(httpUrl);
                fileNameTemp = fileNameFinal + "_temp.jpg";
                fileNameFinal += ".jpg";
            }
        } else if (location.volume_id != 0 && location.local_id != 0) {
            fileNameTemp = location.volume_id + "_" + location.local_id + "_temp.jpg";
            fileNameFinal = location.volume_id + "_" + location.local_id + ".jpg";
            if (key != null) {
                fileNameIv = location.volume_id + "_" + location.local_id + ".iv";
            }
            if (datacenter_id == Integer.MIN_VALUE || location.volume_id == Integer.MIN_VALUE) {
                onlyCache = true;
            }
        } else {
            ignoreCache = true;
            needBitmapCreate = false;
            fileNameTemp = datacenter_id + "_" + location.id + "_temp" + ext;
            fileNameFinal = datacenter_id + "_" + location.id + ext;
            if (key != null) {
                fileNameIv = datacenter_id + "_" + location.id + ".iv";
            }
        }

        boolean exist;
        if (isLocalFile) {
            cacheFileFinal = new File(fileNameFinal);
        } else {
            cacheFileFinal = new File(AndroidUtilities.getCacheDir(), fileNameFinal);
        }
        final boolean dontDelete = isLocalFile;
        final Long mediaIdFinal = mediaId;
        if ((exist = cacheFileFinal.exists()) && !ignoreCache) {
            FileLoader.cacheOutQueue.postRunnable(new Runnable() {
                @Override
                public void run() {
                    try {
                        int delay = 20;
                        if (FileLoader.getInstance().runtimeHack != null) {
                            delay = 60;
                        }
                        if (mediaIdFinal != null) {
                            delay = 0;
                        }
                        if (delay != 0 && FileLoader.lastCacheOutTime != 0 && FileLoader.lastCacheOutTime > System.currentTimeMillis() - delay) {
                            Thread.sleep(delay);
                        }
                        FileLoader.lastCacheOutTime = System.currentTimeMillis();
                        if (state != 1) {
                            return;
                        }

                        if (needBitmapCreate) {
                            BitmapFactory.Options opts = new BitmapFactory.Options();

                            float w_filter = 0;
                            float h_filter = 0;
                            if (filter != null) {
                                String args[] = filter.split("_");
                                w_filter = Float.parseFloat(args[0]) * AndroidUtilities.density;
                                h_filter = Float.parseFloat(args[1]) * AndroidUtilities.density;
                                opts.inJustDecodeBounds = true;

                                if (mediaIdFinal != null) {
                                    MediaStore.Images.Thumbnails.getThumbnail(ApplicationLoader.applicationContext.getContentResolver(), mediaIdFinal, MediaStore.Images.Thumbnails.MINI_KIND, opts);
                                } else {
                                    BitmapFactory.decodeFile(cacheFileFinal.getAbsolutePath(), opts);
                                }

                                float photoW = opts.outWidth;
                                float photoH = opts.outHeight;
                                float scaleFactor = Math.max(photoW / w_filter, photoH / h_filter);
                                if (scaleFactor < 1) {
                                    scaleFactor = 1;
                                }
                                opts.inJustDecodeBounds = false;
                                opts.inSampleSize = (int)scaleFactor;
                            }

                            if (filter == null) {
                                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                            } else {
                                opts.inPreferredConfig = Bitmap.Config.RGB_565;
                            }
                            opts.inDither = false;
                            if (mediaIdFinal != null) {
                                image = MediaStore.Images.Thumbnails.getThumbnail(ApplicationLoader.applicationContext.getContentResolver(), mediaIdFinal, MediaStore.Images.Thumbnails.MINI_KIND, null);
                            }
                            if (image == null) {
                                FileInputStream is = new FileInputStream(cacheFileFinal);
                                image = BitmapFactory.decodeStream(is, null, opts);
                                is.close();
                            }
                            if (image == null) {
                                if (!dontDelete && (cacheFileFinal.length() == 0 || filter == null)) {
                                   cacheFileFinal.delete();
                                }
                            } else {
                                if (filter != null) {
                                    float bitmapW = image.getWidth();
                                    float bitmapH = image.getHeight();
                                    if (bitmapW != w_filter && bitmapW > w_filter) {
                                        float scaleFactor = bitmapW / w_filter;
                                        Bitmap scaledBitmap = Bitmap.createScaledBitmap(image, (int)w_filter, (int)(bitmapH / scaleFactor), true);
                                        if (image != scaledBitmap) {
                                            image.recycle();
                                            image = scaledBitmap;
                                        }
                                    }

                                }
                                if (FileLoader.getInstance().runtimeHack != null) {
                                    FileLoader.getInstance().runtimeHack.trackFree(image.getRowBytes() * image.getHeight());
                                }
                            }
                        }
                        Utilities.stageQueue.postRunnable(new Runnable() {
                            @Override
                            public void run() {
                                if (image == null) {
                                    delegate.didFailedLoadingFile(FileLoadOperation.this);
                                } else {
                                    delegate.didFinishLoadingFile(FileLoadOperation.this);
                                }
                            }
                        });
                    } catch (Exception e) {
                        if (!dontDelete && cacheFileFinal.length() == 0) {
                            cacheFileFinal.delete();
                        }
                        Utilities.stageQueue.postRunnable(new Runnable() {
                            @Override
                            public void run() {
                                delegate.didFailedLoadingFile(FileLoadOperation.this);
                            }
                        });
                        FileLog.e("tmessages", e);
                    }
                }
            });
        } else {
            if (onlyCache) {
                cleanup();
                Utilities.stageQueue.postRunnable(new Runnable() {
                    @Override
                    public void run() {
                        delegate.didFailedLoadingFile(FileLoadOperation.this);
                    }
                });
                return;
            }
            cacheFileTemp = new File(AndroidUtilities.getCacheDir(), fileNameTemp);
            if (cacheFileTemp.exists()) {
                downloadedBytes = (int)cacheFileTemp.length();
                nextDownloadOffset = downloadedBytes = downloadedBytes / 1024 * 1024;
            }
            if (fileNameIv != null) {
                cacheIvTemp = new File(AndroidUtilities.getCacheDir(), fileNameIv);
                try {
                    fiv = new RandomAccessFile(cacheIvTemp, "rws");
                    long len = cacheIvTemp.length();
                    if (len > 0 && len % 32 == 0) {
                        fiv.read(iv, 0, 32);
                    } else {
                        downloadedBytes = 0;
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                    downloadedBytes = 0;
                }
            }
            if (exist) {
                cacheFileFinal.delete();
            }
            try {
                fileOutputStream = new RandomAccessFile(cacheFileTemp, "rws");
                if (downloadedBytes != 0) {
                    fileOutputStream.seek(downloadedBytes);
                }
            } catch (Exception e) {
                FileLog.e("tmessages", e);
            }
            if (fileOutputStream == null) {
                cleanup();
                Utilities.stageQueue.postRunnable(new Runnable() {
                    @Override
                    public void run() {
                        delegate.didFailedLoadingFile(FileLoadOperation.this);
                    }
                });
                return;
            }
            if (httpUrl != null) {
                startDownloadHTTPRequest();
            } else {
                Utilities.stageQueue.postRunnable(new Runnable() {
                    @Override
                    public void run() {
                        if (totalBytesCount != 0 && downloadedBytes == totalBytesCount) {
                            try {
                                onFinishLoadingFile();
                            } catch (Exception e) {
                                delegate.didFailedLoadingFile(FileLoadOperation.this);
                            }
                        } else {
                            startDownloadRequest();
                        }
                    }
                });

            }
        }
    }

    public void cancel() {
        Utilities.stageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                if (state != 1) {
                    return;
                }
                state = 2;
                cleanup();
                if (httpUrl == null) {
                    for (RequestInfo requestInfo : requestInfos) {
                        if (requestInfo.requestToken != 0) {
                            ConnectionsManager.getInstance().cancelRpc(requestInfo.requestToken, true, true);
                        }
                    }
                }
                delegate.didFailedLoadingFile(FileLoadOperation.this);
            }
        });
    }

    private void cleanup() {
        if (httpUrl != null) {
            try {
                if (httpConnectionStream != null) {
                    httpConnectionStream.close();
                }
                httpConnection = null;
                httpConnectionStream = null;
            } catch (Exception e) {
                FileLog.e("tmessages", e);
            }
        } else {
            try {
                if (fileOutputStream != null) {
                    fileOutputStream.close();
                    fileOutputStream = null;
                }
            } catch (Exception e) {
                FileLog.e("tmessages", e);
            }

            try {
                if (fiv != null) {
                    fiv.close();
                    fiv = null;
                }
            } catch (Exception e) {
                FileLog.e("tmessages", e);
            }
            for (RequestInfo requestInfo : delayedRequestInfos) {
                if (requestInfo.response != null) {
                    requestInfo.response.disableFree = false;
                    requestInfo.response.freeResources();
                }
            }
            delayedRequestInfos.clear();
        }
    }

    private void onFinishLoadingFile() throws Exception {
        if (state != 1) {
            return;
        }
        state = 3;
        cleanup();
        if (cacheIvTemp != null) {
            cacheIvTemp.delete();
        }
        final boolean renamed = cacheFileTemp.renameTo(cacheFileFinal);
        if (needBitmapCreate) {
            FileLoader.cacheOutQueue.postRunnable(new Runnable() {
                @Override
                public void run() {
                    int delay = 20;
                    if (FileLoader.getInstance().runtimeHack != null) {
                        delay = 60;
                    }
                    if (FileLoader.lastCacheOutTime != 0 && FileLoader.lastCacheOutTime > System.currentTimeMillis() - delay) {
                        try {
                            Thread.sleep(delay);
                        } catch (Exception e) {
                            FileLog.e("tmessages", e);
                        }
                    }
                    BitmapFactory.Options opts = new BitmapFactory.Options();

                    float w_filter = 0;
                    float h_filter;
                    if (filter != null) {
                        String args[] = filter.split("_");
                        w_filter = Float.parseFloat(args[0]) * AndroidUtilities.density;
                        h_filter = Float.parseFloat(args[1]) * AndroidUtilities.density;

                        opts.inJustDecodeBounds = true;
                        BitmapFactory.decodeFile(cacheFileFinal.getAbsolutePath(), opts);
                        float photoW = opts.outWidth;
                        float photoH = opts.outHeight;
                        float scaleFactor = Math.max(photoW / w_filter, photoH / h_filter);
                        if (scaleFactor < 1) {
                            scaleFactor = 1;
                        }
                        opts.inJustDecodeBounds = false;
                        opts.inSampleSize = (int) scaleFactor;
                    }

                    if (filter == null) {
                        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    } else {
                        opts.inPreferredConfig = Bitmap.Config.RGB_565;
                    }

                    opts.inDither = false;
                    try {
                        if (renamed) {
                            image = BitmapFactory.decodeStream(new FileInputStream(cacheFileFinal), null, opts);
                        } else {
                            try {
                                image = BitmapFactory.decodeStream(new FileInputStream(cacheFileTemp), null, opts);
                            } catch (Exception e) {
                                FileLog.e("tmessages", e);
                                image = BitmapFactory.decodeStream(new FileInputStream(cacheFileFinal), null, opts);
                            }
                        }
                        if (filter != null && image != null) {
                            float bitmapW = image.getWidth();
                            float bitmapH = image.getHeight();
                            if (bitmapW != w_filter && bitmapW > w_filter) {
                                float scaleFactor = bitmapW / w_filter;
                                Bitmap scaledBitmap = Bitmap.createScaledBitmap(image, (int) w_filter, (int) (bitmapH / scaleFactor), true);
                                if (image != scaledBitmap) {
                                    image.recycle();
                                    image = scaledBitmap;
                                }
                            }

                        }
                        if (image != null && FileLoader.getInstance().runtimeHack != null) {
                            FileLoader.getInstance().runtimeHack.trackFree(image.getRowBytes() * image.getHeight());
                        }
                        if (image != null) {
                            delegate.didFinishLoadingFile(FileLoadOperation.this);
                        } else {
                            delegate.didFailedLoadingFile(FileLoadOperation.this);
                        }
                    } catch (Exception e) {
                        FileLog.e("tmessages", e);
                        delegate.didFailedLoadingFile(FileLoadOperation.this);
                    }
                }
            });
        } else {
            delegate.didFinishLoadingFile(FileLoadOperation.this);
        }
    }

    private void startDownloadHTTPRequest() {
        if (state != 1) {
            return;
        }
        if (httpConnection == null) {
            try {
                URL downloadUrl = new URL(httpUrl);
                httpConnection = downloadUrl.openConnection();
                httpConnection.setConnectTimeout(5000);
                httpConnection.setReadTimeout(5000);
                httpConnection.connect();
                httpConnectionStream = httpConnection.getInputStream();
            } catch (Exception e) {
                FileLog.e("tmessages", e);
                cleanup();
                Utilities.stageQueue.postRunnable(new Runnable() {
                    @Override
                    public void run() {
                        delegate.didFailedLoadingFile(FileLoadOperation.this);
                    }
                });
                return;
            }
        }

        try {
            byte[] data = new byte[1024 * 2];
            int readed = httpConnectionStream.read(data);
            if (readed > 0) {
                fileOutputStream.write(data, 0, readed);
                FileLoader.fileLoaderQueue.postRunnable(new Runnable() {
                    @Override
                    public void run() {
                        startDownloadHTTPRequest();
                    }
                });
            } else if (readed == -1) {
                cleanup();
                Utilities.stageQueue.postRunnable(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            onFinishLoadingFile();
                        } catch (Exception e) {
                            delegate.didFailedLoadingFile(FileLoadOperation.this);
                        }
                    }
                });
            } else {
                cleanup();
                Utilities.stageQueue.postRunnable(new Runnable() {
                    @Override
                    public void run() {
                        delegate.didFailedLoadingFile(FileLoadOperation.this);
                    }
                });
            }
        } catch (Exception e) {
            cleanup();
            FileLog.e("tmessages", e);
            Utilities.stageQueue.postRunnable(new Runnable() {
                @Override
                public void run() {
                    delegate.didFailedLoadingFile(FileLoadOperation.this);
                }
            });
        }
    }

    private void processRequestResult(RequestInfo requestInfo, TLRPC.TL_error error) {
        requestInfos.remove(requestInfo);
        if (error == null) {
            try {
                if (downloadedBytes != requestInfo.offset) {
                    if (state == 1) {
                        delayedRequestInfos.add(requestInfo);
                        requestInfo.response.disableFree = true;
                    }
                    return;
                }

                if (requestInfo.response.bytes == null || requestInfo.response.bytes.limit() == 0) {
                    onFinishLoadingFile();
                    return;
                }
                if (key != null) {
                    Utilities.aesIgeEncryption(requestInfo.response.bytes.buffer, key, iv, false, true, 0, requestInfo.response.bytes.limit());
                }
                if (fileOutputStream != null) {
                    FileChannel channel = fileOutputStream.getChannel();
                    channel.write(requestInfo.response.bytes.buffer);
                }
                if (fiv != null) {
                    fiv.seek(0);
                    fiv.write(iv);
                }
                downloadedBytes += requestInfo.response.bytes.limit();
                if (totalBytesCount > 0 && state == 1) {
                    delegate.didChangedLoadProgress(FileLoadOperation.this,  Math.min(1.0f, (float)downloadedBytes / (float)totalBytesCount));
                }

                for (int a = 0; a < delayedRequestInfos.size(); a++) {
                    RequestInfo delayedRequestInfo = delayedRequestInfos.get(a);
                    if (downloadedBytes == delayedRequestInfo.offset) {
                        delayedRequestInfos.remove(a);
                        processRequestResult(delayedRequestInfo, null);
                        delayedRequestInfo.response.disableFree = false;
                        delayedRequestInfo.response.freeResources();
                        delayedRequestInfo = null;
                        break;
                    }
                }

                if (totalBytesCount != downloadedBytes && downloadedBytes % downloadChunkSize == 0 || totalBytesCount > 0 && totalBytesCount > downloadedBytes) {
                    startDownloadRequest();
                } else {
                    onFinishLoadingFile();
                }
            } catch (Exception e) {
                cleanup();
                delegate.didFailedLoadingFile(FileLoadOperation.this);
                FileLog.e("tmessages", e);
            }
        } else {
            if (error.text.contains("FILE_MIGRATE_")) {
                String errorMsg = error.text.replace("FILE_MIGRATE_", "");
                Scanner scanner = new Scanner(errorMsg);
                scanner.useDelimiter("");
                Integer val;
                try {
                    val = scanner.nextInt();
                } catch (Exception e) {
                    val = null;
                }
                if (val == null) {
                    cleanup();
                    delegate.didFailedLoadingFile(FileLoadOperation.this);
                } else {
                    datacenter_id = val;
                    nextDownloadOffset = 0;
                    startDownloadRequest();
                }
            } else if (error.text.contains("OFFSET_INVALID")) {
                if (downloadedBytes % downloadChunkSize == 0) {
                    try {
                        onFinishLoadingFile();
                    } catch (Exception e) {
                        FileLog.e("tmessages", e);
                        cleanup();
                        delegate.didFailedLoadingFile(FileLoadOperation.this);
                    }
                } else {
                    cleanup();
                    delegate.didFailedLoadingFile(FileLoadOperation.this);
                }
            } else {
                if (location != null) {
                    FileLog.e("tmessages", "" + location + " id = " + location.id + " access_hash = " + location.access_hash + " volume_id = " + location.local_id + " secret = " + location.secret);
                }
                cleanup();
                delegate.didFailedLoadingFile(FileLoadOperation.this);
            }
        }
    }

    private void startDownloadRequest() {
        if (state != 1 || totalBytesCount > 0 && nextDownloadOffset >= totalBytesCount || requestInfos.size() + delayedRequestInfos.size() >= maxDownloadRequests) {
            return;
        }
        int count = 1;
        if (totalBytesCount > 0) {
            count = Math.max(0, maxDownloadRequests - requestInfos.size() - delayedRequestInfos.size());
        }

        for (int a = 0; a < count; a++) {
            if (totalBytesCount > 0 && nextDownloadOffset >= totalBytesCount) {
                break;
            }
            boolean isLast = totalBytesCount <= 0 || a == count - 1 || totalBytesCount > 0 && nextDownloadOffset + downloadChunkSize >= totalBytesCount;
            TLRPC.TL_upload_getFile req = new TLRPC.TL_upload_getFile();
            req.location = location;
            req.offset = nextDownloadOffset;
            req.limit = downloadChunkSize;
            nextDownloadOffset += downloadChunkSize;

            final RequestInfo requestInfo = new RequestInfo();
            requestInfos.add(requestInfo);
            requestInfo.offset = req.offset;
            requestInfo.requestToken = ConnectionsManager.getInstance().performRpc(req, new RPCRequest.RPCRequestDelegate() {
                @Override
                public void run(TLObject response, TLRPC.TL_error error) {
                    requestInfo.response = (TLRPC.TL_upload_file) response;
                    processRequestResult(requestInfo, error);
                }
            }, null, true, RPCRequest.RPCRequestClassDownloadMedia, datacenter_id, isLast);
        }
    }
}
