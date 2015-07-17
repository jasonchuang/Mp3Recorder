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
 *
 */
#include <string.h>
#include <jni.h>
#include <android/bitmap.h>

#include "libavformat/avformat.h"
#include "libswresample/swresample.h"
#include "libavcodec/avcodec.h"
//jni/ffmpeg-2.5//libavcodec/avcodec.h
#include "debug.h"

#define AUDIO_CHANNAL_COUNT 1
#define AUDIO_BIT_RATE 32000
#define AUDIO_SAMPLE_RATE 16000

AVFormatContext *gAVFormatContext;
AVOutputFormat *gOutputFormat;
AVCodecContext *gAudioAVCodecContext;
AVCodec *mAudioCodec;
AVFrame *gAudioAVFrame;
AVStream  *mAudioStream;
struct SwrContext *gAudioSwrContext;
int gAudioFrameNumber;

uint8_t *m_audio_outbuf;
int m_audio_outbuf_size;
jbyte* g_buffer;

AVStream *add_audio_stream(AVFormatContext *oc, AVCodec **codec, enum AVCodecID codec_id)
{
	AVCodecContext *c = 0;
	AVStream *st = 0;

	/* find the encoder */
	*codec = avcodec_find_encoder(codec_id);
	if (!(*codec)) {
		LOGI("####### FFWriter::add_audio_stream() Could not find codec for '%s'\n", avcodec_get_name(codec_id));
		return 0;
	}

    LOGI("add_audio_stream AVCodec name: %s", avcodec_get_name(codec_id));

	st = avformat_new_stream(oc, *codec);
	if (!st) {
		LOGI("####### FFWriter::add_audio_stream() Could not alloc stream");
		return 0;
	}
//    gAudioAVCodecContext = avcodec_alloc_context3(pCodec);
//    gAudioAVCodecContext->channels = AUDIO_CHANNAL_COUNT;
//    gAudioAVCodecContext->bit_rate = AUDIO_BIT_RATE;
//    gAudioAVCodecContext->sample_rate = AUDIO_SAMPLE_RATE;
//    gAudioAVCodecContext->sample_fmt = AV_SAMPLE_FMT_S16;
	st->id = oc->nb_streams-1;
	c = st->codec;
	st->id = 0;

	c->codec_id = codec_id;
	LOGI("####### FFWriter::add_audio_stream() Audio codec_id = %d", codec_id);

	c->codec_type  = AVMEDIA_TYPE_AUDIO;
	c->bit_rate    = 64000;  //cannot >= 112000 (16000*7)
	c->sample_rate = 44100;
	c->channels    = 2;
	c->sample_fmt  = AV_SAMPLE_FMT_S16P;
    c->channel_layout = AV_CH_LAYOUT_STEREO;

//	c->sample_fmt = AV_SAMPLE_FMT_FLTP;

	//c->frame_size = (m_sampleRate == 8000) ? (4096*m_channels) : (320*m_channels);
//	LOGI("######## FFWriter::add_audio_stream() sampleRate=%d", m_sampleRate);

	if(oc->oformat->flags & AVFMT_GLOBALHEADER)
		c->flags |= CODEC_FLAG_GLOBAL_HEADER;

	return st;
}//add_audio_stream()

int open_audio(AVFormatContext *oc, AVCodec *codec, AVStream *st)
{
	int ret;
	AVCodecContext *c = 0;

	c = st->codec;

	/* open it */
	ret = avcodec_open2(c, codec, NULL);
	if (ret < 0) {
		LOGI("######## FFWriter::open_audio() Could not open audio codec: %s\n", av_err2str(ret));
		return 0;
	}

	m_audio_outbuf_size = 10000;
	m_audio_outbuf = (uint8_t *)av_malloc(m_audio_outbuf_size);

	return 1;
}//open_audio()

void log_callback_help(void *ptr, int level, const char *fmt, va_list vl) {
	//LOGI(fmt, vl);
}

jint
Java_com_jasonsoft_mp3recorder_Mp3RecorderActivity_nativeInitAudio(JNIEnv* env, jobject thiz, jstring pFilename)
{
    char *mp3FileName = (char *)(*env)->GetStringUTFChars(env, pFilename, NULL);
    // open the video file
//    if (avformat_open_input(&gAVFormatContext, videoFileName, NULL, NULL) != 0) {
    LOGE("Mp3 filename: %s", mp3FileName);

    av_register_all();
	av_log_set_callback(log_callback_help);
	av_log_set_level(AV_LOG_VERBOSE);
//    LOGI("nativeInitAudio");
    int ret = avformat_alloc_output_context2(&gAVFormatContext, NULL, NULL, mp3FileName);
	if (!gAVFormatContext) {
		LOGI("Could not deduce output format from file extension: using MPEG :%s  %s.\n",
                mp3FileName, av_err2str(ret));
	}

    gOutputFormat = gAVFormatContext->oformat;

	if (!gOutputFormat)	{
		LOGI("####### FFWriter::init() Could not find suitable output format");
		return false;
    }
//            LOGI("after avformat_alloc_output_context2 G726 id:%d", AV_CODEC_ID_ADPCM_G726);
                //using MPEG4 as video codec
                //    m_fmt->video_codec = AV_CODEC_ID_MPEG4;
                //    //  m_fmt->audio_codec = AV_CODEC_ID_MP3;
//   AVCodec *codec = avcodec_find_encoder(AV_CODEC_ID_AAC);
   enum AVCodecID codecId = AV_CODEC_ID_MP3;
//   enum AVCodecID codecId = AV_CODEC_ID_AAC;
   AVCodec *codec = avcodec_find_encoder(codecId);
   if (codec == NULL) {
       LOGI("Could not find encoder for '%s'\n", avcodec_get_name(codecId));
       return 0;
   }
    gOutputFormat->audio_codec = AV_CODEC_ID_MP3;
	snprintf(gAVFormatContext->filename, sizeof(gAVFormatContext->filename), "%s", mp3FileName);

	if (gOutputFormat->audio_codec != CODEC_ID_NONE)
		mAudioStream = add_audio_stream(gAVFormatContext, &mAudioCodec, gOutputFormat->audio_codec);

    int	openOK = open_audio(gAVFormatContext, mAudioCodec, mAudioStream);
	if (!openOK) {
		LOGI("######## FFWriter::init() openOK failed");
		return false;
	}

	av_dump_format(gAVFormatContext, 0, mp3FileName, 1);
	if (!(gOutputFormat->flags & AVFMT_NOFILE))	{
		if (avio_open(&gAVFormatContext->pb, mp3FileName, AVIO_FLAG_WRITE) < 0) {
			LOGI("####### FFWriter::init() Could not open %s %s", mp3FileName, av_err2str(ret));
			return false;
		}
	}
	ret = avformat_write_header(gAVFormatContext, NULL);
	if (ret < 0) {
		LOGI("Error occurred when writing header: %s\n", av_err2str(ret));
		return false;
	}
//    av_log_set_callback(ff_audio_log_callback);

//    AVCodec *pCodec = avcodec_find_decoder(CODEC_ID_AAC);
//    gAudioAVCodecContext = avcodec_alloc_context3(pCodec);
//    gAudioAVCodecContext->channels = AUDIO_CHANNAL_COUNT;
//    gAudioAVCodecContext->bit_rate = AUDIO_BIT_RATE;
//    gAudioAVCodecContext->sample_rate = AUDIO_SAMPLE_RATE;
//    gAudioAVCodecContext->sample_fmt = AV_SAMPLE_FMT_S16;
//
    LOGI("AVCodec name: %s", codec->name);
    LOGI("AVCodec long name: %s", codec->long_name);
//    if (pCodec == NULL) {
//        LOGE("unsupported codec");
//        return -1;
//    }
//
//    if (avcodec_open2(gAudioAVCodecContext, pCodec, NULL) < 0) {
//        LOGE("could not open codec");
//        return -1;
//    }
//
//    gAudioAVFrame = avcodec_alloc_frame();
//    avcodec_get_frame_defaults(gAudioAVFrame);
//
    return 0;
}


jint
Java_com_jasonsoft_mp3recorder_Mp3RecorderActivity_nativeAddAudioFrame(JNIEnv* env, jobject thiz,
        jbyteArray data, jint len)
{
//    jbyte *pJbyteBuffer = 0;
//
//    if (data) {
//        pJbyteBuffer = (char *) (*env)->GetByteArrayElements(env, data, 0);
//    } else {
//        return;
//    }
//
//    int framefinished;
//    AVPacket packet;
//    av_init_packet(&packet);
//    packet.data = (unsigned char *)(pJbyteBuffer);
//    packet.size = len;
//
//    int usedLen = avcodec_decode_audio4(gAudioAVCodecContext, gAudioAVFrame, &framefinished, &packet);
//    if (pJbyteBuffer) {
//        (*env)->ReleaseByteArrayElements(env, data, pJbyteBuffer, JNI_ABORT);
//    }
//
//    int data_size = av_samples_get_buffer_size(NULL, gAudioAVCodecContext->channels,
//            gAudioAVFrame->nb_samples, gAudioAVCodecContext->sample_fmt, 0);
//
//    if (framefinished && usedLen > 0) {
//        uint8_t pTemp[data_size];
//        uint8_t *pOut = (uint8_t *)&pTemp;
//        swr_init(gAudioSwrContext);
//        swr_convert(gAudioSwrContext, (uint8_t **)(&pOut), gAudioAVFrame->nb_samples,
//                (const uint8_t **)gAudioAVFrame->extended_data,
//                gAudioAVFrame->nb_samples);
//
//        data_size = av_samples_get_buffer_size(NULL, gAudioAVCodecContext->channels,
//                gAudioAVFrame->nb_samples, AV_SAMPLE_FMT_S16, 0);
//        memcpy(g_buffer, pOut, data_size);
//        gAudioFrameNumber++;
//
//        av_free_packet(&packet);
//        return data_size;
//    }
//
    return -1;
}

jint
Java_com_jasonsoft_mp3recorder_Mp3RecorderActivity_nativeCloseAudio(JNIEnv* env, jobject thiz)
{
    LOGI("nativeCloseAudio");
//    av_free(gAudioAVFrame);
//    avcodec_close(gAudioAVCodecContext);

//	if (gIsInitOK) > 0)
//		av_write_trailer(m_oc);
//
//	if (m_audio_st)
//		close_audio(m_oc, m_audio_st);
//
//	for(unsigned int i = 0; i < m_oc->nb_streams; i++)
//	{
//		av_freep(&m_oc->streams[i]->codec);
//		av_freep(&m_oc->streams[i]);
//	}
//
//	if (m_isInitOK)
//	{
//		if (!(m_fmt->flags & AVFMT_NOFILE))
//			avio_close(m_oc->pb);
//	}
//
//	av_free(m_oc);
//	m_oc = 0;
//	m_isInitOK = false;
//
    return 0;
}

