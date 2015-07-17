package com.jasonsoft.mp3recorder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.graphics.Bitmap;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Process;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.ToggleButton;
import android.widget.Toast;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;

public class Mp3RecorderActivity extends Activity implements View.OnClickListener {
    private static final String TAG = Mp3RecorderActivity.class.getSimpleName();

    public static final String KEY_DESTINATION_IP_ADDRESS = "destination_ip_address";
    private static final String LOCAL_IP_ADDRESS = "127.0.0.1";

    public static final int AUDIO_FRAME_LENGTH = 3200; // 200ms audio frame
    public static final int ONE_SECOND_AUDIO_BUFFER_SIZE = 5 * AUDIO_FRAME_LENGTH;
    public static final int FOUR_SECONDS_AUDIO_BUFFER_SIZE = 4 * ONE_SECOND_AUDIO_BUFFER_SIZE;
    public static final int TEN_SECONDS_AUDIO_BUFFER_SIZE = 10 * ONE_SECOND_AUDIO_BUFFER_SIZE;

    private static final int BUFFER_SIZE = 8192; // 8K
    private static final int FRAME_BUFFER_SIZE = 500 * 1024; // 500K
    private static final int VIDEO_UDP_PORT = 5006;
    private static final int AUDIO_UDP_PORT = 5004;

    private static final int VIDEO_WIDTH = 640;
    private static final int VIDEO_HEIGHT = 480;
    private static final int VIDEO_FRAME_RATE = 10;
    private static final int VIDEO_BIT_RATE = 500000;

    private static final int V_P_X_CC_BYTE_INDEX = 0;
    private static final int M_PT_BYTE_INDEX = 1;
    private static final int SEQ_START_BYTE_INDEX = 2;
    private static final int TS_START_BYTE_INDEX = 4;
    private static final int SSRC_START_BYTE_INDEX = 8;
    private static final int FU_IDENTIFIER_BYTE_INDEX = 12;
    private static final int FU_HEADER_INDEX = 13;
    private static final int RTP_HEADER_SIZE = 12;
    private static final int H264_RTP_HEADER_SIZE = 13;

    private static final int NAL_UNIT_SINGLE_PACKET_START = 1;
    private static final int NAL_UNIT_SINGLE_PACKET_END = 23;
    private static final int NAL_UNIT_FU_A = 28;
    private static final int NAL_UNIT_FU_B = 29;

    private TextView mSentStats;
    private TextView mReceivedStats;
    private Bitmap mLocalBitmap = null;
    private Bitmap mRemoteBitmap = null;
    private ToggleButton mRecordButton;
    private ToggleButton mViewButton;
    private long mCurrentFrameTs = 0;
    private boolean mIsViewing;
    private boolean mIsRecording;
    private SharedPreferences mPrefs;
    private AlertDialog mDialog;
    private Context mContext;
    private AudioRecord mAudioRecord;
    private Thread mRecordingThread = null;
    private int mSampleRateInHz = 44100;

    static {
        System.loadLibrary("avutil-54");
        System.loadLibrary("avcodec-56");
        System.loadLibrary("avformat-56");
        System.loadLibrary("avformat-56");
        System.loadLibrary("swscale-3");
        System.loadLibrary("swresample-1");

        System.loadLibrary("media_api");
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        mRecordButton = (ToggleButton) findViewById(R.id.record_button);
        // mViewButton = (ToggleButton) findViewById(R.id.view_button);
        mSentStats = (TextView) findViewById(R.id.sent_stats);
        mReceivedStats = (TextView) findViewById(R.id.received_stats);

        mRecordButton.setOnClickListener(this);
        // mViewButton.setOnClickListener(this);
        mIsViewing = false;

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mContext = this;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_switch_camera:
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
        case R.id.record_button:
            if (!mIsRecording) {
                startAudioRecording();
            } else {
                Toast.makeText(this, "Stop recording and save file as ...", Toast.LENGTH_SHORT).show();
                stopAudioRecording();
            }
//                stopMediaReceiverAsyncTask();
//                mIsViewing = false;
//                mViewButton.setChecked(false);
//            if (Utils.isInvalidIpAddress(destinationIpAddress)) {
//                // alrert dialog not start
//
//                mRecordButton.setChecked(false);
//                showInvalidIpAddressDialog();
//                return;
//            }
//
            break;
//        case R.id.view_button:
//            if (!mIsViewing) {
//                mIsViewing = true;
//                mViewButton.setChecked(true);
//            } else {
//                stopMediaReceiverAsyncTask();
//                mIsViewing = false;
//                mViewButton.setChecked(false);
//            }
//            break;
        default:
            break;
        }
    }

    private void stopMediaReceiverAsyncTask() {
    }

    private void showInvalidIpAddressDialog() {
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }

        final AlertDialog.Builder builder = new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_LIGHT);
        builder.setTitle(R.string.error_dialog_title);
        builder.setMessage(R.string.invalid_ip_address).setPositiveButton(R.string.ok_button, null);
        mDialog = builder.create();
        mDialog.show();
    }

    public void onBitrateUpdate(long bitrate) {
        Log.d(TAG, "Bitrate: "+bitrate);
        mSentStats.setText("Streaming bitrate " + bitrate);
    }

	public void onDestroy() {
		super.onDestroy();
        stopMediaReceiverAsyncTask();
	}

    private synchronized void startAudioRecording() {
//        final int recordBufferSize = FOUR_SECONDS_AUDIO_BUFFER_SIZE;

        final int minBufferSize = AudioRecord.getMinBufferSize(mSampleRateInHz, AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        Log.d(TAG, "startAudioRecording mSampleRateInHz:" + mSampleRateInHz);
        Log.d(TAG, "startAudioRecording minBufferSize:" + minBufferSize);
//        mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, mSampleRateInHz,
        mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, mSampleRateInHz,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, minBufferSize * 2);

        mRecordingThread = new Thread(new Runnable() {
            @Override
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
                byte[] audioData = new byte[minBufferSize];
                int len = 0;

                while (!Thread.interrupted()) {
                    len = mAudioRecord.read(audioData, 0, minBufferSize);
        //            Log.e(TAG,"read audio len:" + len);
                    if (len ==  AudioRecord.ERROR_INVALID_OPERATION || len == AudioRecord.ERROR_BAD_VALUE) {
                        Log.e(TAG,"An error occured with the AudioRecord API !");
                    } else {
                        // To avoid "RecordThread: buffer overflow",
                        // Processing audio data sent off the recording Thread
                    }
                }
            }
        });

        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMddHHmmss");
        String fileName = simpleDateFormat.format(new Date());
        Toast.makeText(this, "Start recording." + fileName, Toast.LENGTH_SHORT).show();

        nativeInitAudio("/sdcard/" + fileName + ".mp3");
        mAudioRecord.startRecording();
        mRecordingThread.start();
        mIsRecording = true;
    }

    public synchronized void stopAudioRecording() {
        Log.d(TAG, "stopAudioRecording: ");
        if (mIsRecording) {
            nativeCloseAudio();
            mRecordingThread.interrupt();
            mAudioRecord.stop();
            mAudioRecord.release();
            mAudioRecord = null;
            mIsRecording = false;
        }
    }

    // For audio streaming
    public native int nativeInitAudio(String fileName);
    public native int nativeAddAudioFrame(byte[] inbuf, int len);
    public native int nativeCloseAudio();
}
