package com.jasonsoft.mp3recorder;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AudioEffect.Descriptor;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.util.Log;

import java.util.ArrayList;

public class InstaVideoAudioStream {
    private static final String TAG = InstaVideoAudioStream.class.getSimpleName();

    public static final int AUDIO_FRAME_LENGTH = 3200; // 200ms audio frame
    public static final int ONE_SECOND_AUDIO_BUFFER_SIZE = 5 * AUDIO_FRAME_LENGTH;
    public static final int FOUR_SECONDS_AUDIO_BUFFER_SIZE = 4 * ONE_SECOND_AUDIO_BUFFER_SIZE;
    public static final int TEN_SECONDS_AUDIO_BUFFER_SIZE = 10 * ONE_SECOND_AUDIO_BUFFER_SIZE;

    private int mSampleRateInHz;
    private boolean mStreaming = false;
    private ArrayList<String> mOutputAgentUids;
    private AudioRecord mAudioRecord;
    private AcousticEchoCanceler mAEC;
    private HandlerThread mSendAudioHandlerThread = null;
    private Handler mSendAudioHandler = null;
    private Thread mRecordingThread = null;

    private class SendAudioHandler extends Handler {
        public static final int MSG_SEND_AUDIO_DATA = 0;

        public SendAudioHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MSG_SEND_AUDIO_DATA:
                break;

            default:
                break;
            }
        }
    }

    public InstaVideoAudioStream(int sampleRateInHz) {
        mSampleRateInHz = sampleRateInHz;
    }

    public synchronized void start() {
        if (!mStreaming) {
            encodeWithAudioConnectionManager();
        }
    }

    public synchronized void setOutputAgentUidList(ArrayList<String> agentUids) {
        mOutputAgentUids = agentUids;
    }

    protected void encodeWithAudioConnectionManager() {
        final int minBufferSize = AUDIO_FRAME_LENGTH;
        final int recordBufferSize = FOUR_SECONDS_AUDIO_BUFFER_SIZE;
        Log.d(TAG, "encodeWithAudioConnectionManager mSampleRateInHz:" + mSampleRateInHz);

        mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, mSampleRateInHz,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recordBufferSize);

        if (AcousticEchoCanceler.isAvailable()) {
            initAEC(mAudioRecord.getAudioSessionId());
        }

        mSendAudioHandlerThread = new HandlerThread("[Audio Send]", Process.THREAD_PRIORITY_BACKGROUND);
        mRecordingThread = new Thread(new Runnable() {
            @Override
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
                byte[] audioData = new byte[minBufferSize];
                int len = 0;

                while (!Thread.interrupted()) {
                    len = mAudioRecord.read(audioData, 0, minBufferSize);
                    if (len ==  AudioRecord.ERROR_INVALID_OPERATION || len == AudioRecord.ERROR_BAD_VALUE) {
                        Log.e(TAG,"An error occured with the AudioRecord API !");
                    } else if (mSendAudioHandler != null) {
                        // To avoid "RecordThread: buffer overflow",
                        // Processing audio data sent off the recording Thread
                        Message message = mSendAudioHandler.obtainMessage(SendAudioHandler.MSG_SEND_AUDIO_DATA);
                        message.obj = audioData;
                        message.arg1 = len;
                        mSendAudioHandler.sendMessage(message);
                    }
                }
            }
        });

        mSendAudioHandlerThread.start();
        mSendAudioHandler = new SendAudioHandler(mSendAudioHandlerThread.getLooper());
        mAudioRecord.startRecording();
        mRecordingThread.start();
        mStreaming = true;
    }

    public boolean initAEC(int audioSession) {
        Log.d(TAG, "initAEC audioSession:" + audioSession);
        if (mAEC != null) {
            return false;
        }

        mAEC = AcousticEchoCanceler.create(audioSession);
        mAEC.setEnabled(true);
        Descriptor descriptor = mAEC.getDescriptor();
        Log.d(TAG, "AcousticEchoCanceler " +
                "name: " + descriptor.name + ", " +
                "implementor: " + descriptor.implementor + ", " +
                "connectMode: " + descriptor.connectMode + ", " +
                "type: " + descriptor.type + ", " +
                "uuid: " + descriptor.uuid);
        return mAEC.getEnabled();
    }

    public boolean releaseAEC() {
        Log.d(TAG, "releaseAEC");
        if (mAEC == null) {
            return false;
        }

        mAEC.setEnabled(false);
        mAEC.release();
        return true;
    }

    /** Stops the stream. */
    public synchronized void stop() {
        if (mStreaming) {
            mRecordingThread.interrupt();
            releaseAEC();
            mAudioRecord.stop();
            mAudioRecord.release();
            mAudioRecord = null;
        }
    }

}
