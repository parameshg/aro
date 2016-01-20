package com.paramg.android.try2;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.att.aro.android.arocollector.ClientPacketWriterImpl;
import com.att.aro.android.arocollector.IClientPacketWriter;
import com.att.aro.android.arocollector.SessionHandler;
import com.att.aro.android.arocollector.socket.IProtectSocket;
import com.att.aro.android.arocollector.socket.SocketNIODataService;
import com.att.aro.android.arocollector.socket.SocketProtector;
import com.att.aro.android.arocollector.tcp.PacketHeaderException;
/*
import com.paramg.android.try2.collector.ClientPacketWriterImpl;
import com.paramg.android.try2.collector.IClientPacketWriter;
import com.paramg.android.try2.collector.SessionHandler;
import com.paramg.android.try2.collector.socket.IProtectSocket;
import com.paramg.android.try2.collector.socket.SocketNIODataService;
import com.paramg.android.try2.collector.socket.SocketProtector;
import com.paramg.android.try2.collector.tcp.PacketHeaderException;
*/
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Socket;
import java.nio.ByteBuffer;

public class CaptureVpnService extends VpnService implements Runnable, IProtectSocket
{
    private static final String TAG = "CaptureVpnService";

    public static final String SERVICE_CLOSE_CMD_INTENT = "com.paramg.android.try2.service.close";

    private Thread mThread;

    private ParcelFileDescriptor mInterface;

    private boolean mServiceValid;

    private PendingIntent mConfigureIntent;

    private Intent mIntent;

    private SocketNIODataService mDataService;

    private Thread mDataServiceThread;

    private BroadcastReceiver receiver = new BroadcastReceiver()
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            Log.d(TAG, "received service close cmd intent");
            unregisterCloseCmdReceiver();
            mServiceValid = false;
            stopSelf();
        }
    };

    private void unregisterCloseCmdReceiver()
    {
        Log.d(TAG, "unregisterCloseCmdReceiver");

        try
        {
            if (receiver != null)
            {
                unregisterReceiver(receiver);

                receiver = null;

                Log.d(TAG, "successfully unregistered receiver");
            }
        }
        catch (Exception e)
        {
            Log.d(TAG, "Ignoring exception in receiver", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        Log.d(TAG, "onStartCommand");

        mIntent = intent;

        registerReceiver(receiver, new IntentFilter(CaptureVpnService.SERVICE_CLOSE_CMD_INTENT));

        // Stop the previous session by interrupting the thread.
        if (mThread != null)
        {
            mThread.interrupt();

            while (mThread.isAlive())
            {
                Log.i(TAG, "Waiting to exit...");

                try
                {
                    Thread.sleep(1000);
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }
            }

            mThread = null;
        }

        // start a new session by creating a new thread
        mThread = new Thread(this, "CaptureVpnThread");
        mThread.start();

        return START_STICKY;
    }

    @Override
    public ComponentName startService(Intent service)
    {
        Log.i(TAG, "startService");

        return super.startService(service);
    }

    @Override
    public boolean stopService(Intent name)
    {
        Log.i(TAG, "stopService(...)");

        mServiceValid = false;

        return super.stopService(name);
    }

    @Override
    public void onRevoke()
    {
        Log.i(TAG, "revoked!, user has turned off VPN");

        super.onRevoke();
    }

    // invoked when user disconnects VPN
    @Override
    public void onDestroy()
    {
        Log.i(TAG, "onDestroy");

        mServiceValid = false;

        unregisterCloseCmdReceiver();

        mDataService.setShutdown(true);

        if (mDataServiceThread != null)
        {
            mDataServiceThread.interrupt();
        }

        try
        {
            if (mInterface != null)
            {
                Log.i(TAG, "mInterface.close");

                mInterface.close();
            }
        }
        catch (IOException e)
        {
            Log.d(TAG, "mInterface.close:" + e.getMessage());

            e.printStackTrace();
        }

        // stop the previous session by interrupting the thread
        if (mThread != null)
        {
            mThread.interrupt();

            while (mThread.isAlive())
            {
                Log.i(TAG, "Waiting to exit...");

                try
                {
                    Thread.sleep(1000);
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }
            }

            mThread = null;
        }
    }

    @Override
    public void run()
    {
        Log.i(TAG, "running service...");

        boolean success = false;

        SocketProtector protector = SocketProtector.getInstance();
        protector.setProtector(this);

        try
        {
            success = startVpnService();
        }
        catch (IOException e)
        {
            Log.e(TAG, e.getMessage());
        }

        if (success)
        {
            try
            {
                startCapture();

                Log.i(TAG, "Capture completed");
            }
            catch (IOException e)
            {
                Log.e(TAG, e.getMessage());
            }
        }
        else
        {
            Log.e(TAG, "Failed to start VPN Service!");
        }
    }

    // setup VPN interface
    private boolean startVpnService() throws IOException
    {
        // if the old interface has exactly the same parameters, use it!
        if (mInterface != null)
        {
            Log.i(TAG, "using the previous interface");
            return false;
        }

        Log.i(TAG, "creating builder...");

        // configure a builder while parsing the parameters
        Builder builder = new Builder()
                .addAddress("192.168.1.123", 32)
                .addRoute("0.0.0.0", 0)
                .setSession("Try")
                .setConfigureIntent(mConfigureIntent);

        if (mInterface != null)
        {
            try
            {
                mInterface.close();
            }
            catch (Exception e)
            {
                Log.e(TAG, "Exception when closing mInterface:" + e.getMessage());
            }
        }

        Log.i(TAG, "builder.establish");
        mInterface = builder.establish();

        if (mInterface != null)
        {
            Log.i(TAG, "VPN established!");
            return true;
        }
        else
        {
            Log.d(TAG, "mInterface is null");
            return false;
        }
    }

    // start background thread to handle client's socket, handle incoming and outgoing packet from VPN interface
    private void startCapture() throws IOException
    {

        Log.i(TAG, "startCapture() :capture starting");

        // Packets to be sent are queued in this input stream.
        FileInputStream clientreader = new FileInputStream(mInterface.getFileDescriptor());

        // Packets received need to be written to this output stream.
        FileOutputStream clientwriter = new FileOutputStream(mInterface.getFileDescriptor());

        // Allocate the buffer for a single packet.
        ByteBuffer packet = ByteBuffer.allocate(65535);
        IClientPacketWriter clientpacketwriter = new ClientPacketWriterImpl(clientwriter);
        SessionHandler handler = SessionHandler.getInstance();
        handler.setWriter(clientpacketwriter);

        //background task for non-blocking socket
        mDataService = new SocketNIODataService();
        mDataService.setWriter(clientpacketwriter);
        mDataServiceThread = new Thread(mDataService);
        mDataServiceThread.start();

        byte[] data;
        int length;

        mServiceValid = true;

        while (mServiceValid)
        {
            //read packet from vpn client
            data = packet.array();
            length = clientreader.read(data);

            if (length > 0)
            {
                Log.d(TAG, "received packet from vpn client: " + length);

                try
                {
                    handler.handlePacket(data, length);
                }
                catch (PacketHeaderException e)
                {
                    Log.e(TAG, e.getMessage());
                }

                packet.clear();
            }
            else
            {
                try
                {
                    Thread.sleep(100);
                }
                catch (InterruptedException e)
                {
                    Log.d(TAG, "Failed to sleep: " + e.getMessage());
                }
            }
        }
        Log.i(TAG, "capture finished: mServiceValid = " + mServiceValid);
    }

    @Override
    public void protectSocket(int socket)
    {
        this.protect(socket);
    }

    @Override
    public void protectSocket(Socket socket)
    {
        this.protect(socket);
    }

    @Override
    public void protectSocket(DatagramSocket socket)
    {
        this.protect(socket);
    }
}