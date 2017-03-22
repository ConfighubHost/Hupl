package eu.imouto.hupl.upload;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedList;
import java.util.Queue;

import eu.imouto.hupl.R;
import eu.imouto.hupl.data.FileToUpload;
import eu.imouto.hupl.data.HistoryDB;
import eu.imouto.hupl.data.HistoryEntry;
import eu.imouto.hupl.ui.UploadNotification;
import eu.imouto.hupl.util.ImageResize;
import eu.imouto.hupl.util.StreamUtil;
import eu.imouto.hupl.util.UriResolver;

public class UploadService extends Service implements UploadProgressReceiver
{
    private SharedPreferences pref;
    private int updatesPerSec;
    private UploadNotification notification;
    private Uploader uploader;
    private HistoryEntry historyEntry;
    private Queue<QueueEntry> uploadQueue = new LinkedList<>();
    private Thread uploaderThread;
    private Thread mainThread;
    private HistoryDB histDb = new HistoryDB(this);
    private boolean uploading = false;
    private long lastUpdate = 0;

    private class QueueEntry
    {
        public String uploader;
        public FileToUpload file;
        public boolean compress;
    }

    public UploadService()
    {
    }

    @Override
    public IBinder onBind(Intent intent)
    {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void onCreate()
    {
        super.onCreate();

        pref = PreferenceManager.getDefaultSharedPreferences(this);
        mainThread = new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                while (true)
                {
                    try
                    {
                        Thread.sleep(500);
                    }
                    catch (InterruptedException e)
                    {
                        e.printStackTrace();
                    }
                    startUpload();
                }
            }
        });
        //mainThread.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        updatesPerSec = Integer.parseInt(pref.getString("notification_updates_per_sec", "5"));
        String act = intent.getAction();
        if (act.equals("eu.imouto.hupl.ACTION_QUEUE_UPLOAD"))
        {
            QueueEntry e = new QueueEntry();
            e.uploader = intent.getStringExtra("uploader");
            e.compress = intent.getBooleanExtra("compress", false);
            Uri uri = intent.getParcelableExtra("uri");
            e.file = UriResolver.uriToFile(this, uri);

            uploadQueue.add(e);
            if (uploading)
                notification.setQueueSize(uploadQueue.size());
            startUpload();
        }
        else if(act.equals("eu.imouto.hupl.ACTION_CANCEL"))
        {
            if (uploader != null)
                uploader.cancel();
        }

        return START_STICKY;
    }

    private void startUpload()
    {
        if (uploading)
            return;

        QueueEntry e = uploadQueue.poll();
        if (e == null)
            return;


        uploader = UploaderFactory.getUploaderByName(this, e.uploader, e.file);
        if (uploader == null)
            return;
        uploader.setProgessReceiver(this);

        //handle thumbnails (and compression) for images
        Bitmap thumb = null;
        if (e.file.isImage())
        {
            byte[] orig = new byte[0];
            try
            {
                orig = StreamUtil.readAllBytes(e.file.stream);
            }
            catch (IOException ex)
            {
                ex.printStackTrace();
                return;
            }

            Bitmap bm = BitmapFactory.decodeByteArray(orig, 0, orig.length);
            thumb = ImageResize.thumbnail(bm);
            if (e.compress)
            {
                int w = Integer.parseInt(pref.getString("image_resize_width", "1000"));
                int h = Integer.parseInt(pref.getString("image_resize_height", "1000"));
                int q = Integer.parseInt(pref.getString("image_resize_quality", "70"));
                bm = ImageResize.resizeToFit(bm, w, h);
                orig = ImageResize.compress(bm, q);
            }
            e.file.stream = new ByteArrayInputStream(orig);
        }

        notification = new UploadNotification(this);
        notification.lights = pref.getBoolean("notification_light", false);
        notification.vibrate = pref.getBoolean("notification_vibrate", false);
        notification.setFileName(e.file.fileName);
        notification.setThumbnail(thumb);

        historyEntry = new HistoryEntry();
        historyEntry.originalName = e.file.fileName;
        historyEntry.mime = e.file.mime;
        historyEntry.uploader = uploader.name;
        historyEntry.thumbnail = thumb;

        startForeground(notification.getId(), notification.getNotification());
        uploaderThread = new Thread(uploader);
        uploaderThread.start();
        uploading = true;
    }

    @Override
    public void onUploadProgress(int uploaded, int fileSize)
    {
        long now = System.currentTimeMillis();
        if ((now - lastUpdate) > 1000/updatesPerSec)
        {
            notification.progress(uploaded, fileSize);
            lastUpdate = now;
        }
    }

    @Override
    public void onUploadFinished(String fileLink)
    {
        historyEntry.link = fileLink;
        histDb.addEntry(historyEntry);

        notification.success(fileLink);
        uploading = false;
        stopFG();
        startUpload();
    }

    @Override
    public void onUploadFailed(String error)
    {
        notification.error(error);
        uploading = false;
        uploadQueue.clear();
        stopFG();
    }

    @Override
    public void onUploadCancelled()
    {
        notification.cancel();
        uploading = false;
        uploadQueue.clear();
        stopFG();
    }

    private void stopFG()
    {
        stopForeground(true);
        notification.newId();
        notification.show();
    }
}