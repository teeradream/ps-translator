// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Native Windows C Bridge for Chiaki PS5/PS4 Remote Play Library (libchiaki)

#include <chiaki/common.h>
#include <chiaki/log.h>
#include <chiaki/session.h>
#include <chiaki/discoveryservice.h>
#include <chiaki/regist.h>
#include <chiaki/opusdecoder.h>
#include <chiaki/controller.h>

#include <libavcodec/avcodec.h>
#include <libswscale/swscale.h>
#include <libavutil/imgutils.h>

#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#define CW_EXPORT __declspec(dllexport)
#else
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#define CW_EXPORT __attribute__((visibility("default")))
#endif

typedef void (*CwDiscoveryHostCallback)(
    int index,
    int total_count,
    int state,
    const char *host_addr,
    const char *host_name,
    const char *host_id,
    const char *host_type,
    const char *running_app_name,
    const char *running_app_titleid
);

typedef void (*CwRegistEventCallback)(
    int event_type,
    int is_ps5,
    const char *server_nickname,
    const uint8_t *server_mac_6,
    const uint8_t *rp_regist_key_16,
    const uint8_t *rp_key_16
);

typedef void (*CwVideoFrameCallback)(
    const uint8_t *bgra_data,
    int width,
    int height,
    int stride
);

typedef void (*CwAudioFrameCallback)(
    const int16_t *pcm_samples,
    int samples_count,
    int channels,
    int sample_rate
);

typedef void (*CwSessionEventCallback)(
    int event_type,
    int quit_reason,
    const char *reason_str
);

static ChiakiLog g_cw_log;
static bool g_cw_initialized = false;

static void cw_log_cb(ChiakiLogLevel level, const char *msg, void *user)
{
    (void)level;
    (void)msg;
    (void)user;
}

CW_EXPORT int cw_init(void)
{
    if (g_cw_initialized)
        return 0;
    chiaki_log_init(&g_cw_log, CHIAKI_LOG_ALL & ~CHIAKI_LOG_VERBOSE, cw_log_cb, NULL);
    ChiakiErrorCode err = chiaki_lib_init();
    if (err == CHIAKI_ERR_SUCCESS)
        g_cw_initialized = true;
    return (int)err;
}

typedef struct cw_discovery_t
{
    ChiakiDiscoveryService service;
    CwDiscoveryHostCallback cb;
} CwDiscovery;

static void cw_discovery_service_cb(ChiakiDiscoveryHost *hosts, size_t hosts_count, void *user)
{
    CwDiscovery *disc = (CwDiscovery *)user;
    if (!disc || !disc->cb)
        return;

    if (hosts_count == 0)
    {
        disc->cb(-1, 0, 0, "", "", "", "", "", "");
        return;
    }

    for (size_t i = 0; i < hosts_count; i++)
    {
        ChiakiDiscoveryHost *h = &hosts[i];
        disc->cb(
            (int)i,
            (int)hosts_count,
            (int)h->state,
            h->host_addr ? h->host_addr : "",
            h->host_name ? h->host_name : "",
            h->host_id ? h->host_id : "",
            h->host_type ? h->host_type : "",
            h->running_app_name ? h->running_app_name : "",
            h->running_app_titleid ? h->running_app_titleid : ""
        );
    }
}

CW_EXPORT void *cw_discovery_start(CwDiscoveryHostCallback cb)
{
    cw_init();
    CwDiscovery *disc = CHIAKI_NEW(CwDiscovery);
    if (!disc)
        return NULL;
    memset(disc, 0, sizeof(CwDiscovery));
    disc->cb = cb;

    struct sockaddr_in bcast_addr;
    memset(&bcast_addr, 0, sizeof(bcast_addr));
    bcast_addr.sin_family = AF_INET;
    bcast_addr.sin_port = htons(987);
    bcast_addr.sin_addr.s_addr = INADDR_BROADCAST;

    ChiakiDiscoveryServiceOptions opts;
    memset(&opts, 0, sizeof(opts));
    opts.hosts_max = 16;
    opts.host_drop_pings = 3;
    opts.ping_ms = 500;
    opts.send_addr = (struct sockaddr *)&bcast_addr;
    opts.send_addr_size = sizeof(bcast_addr);
    opts.cb = cw_discovery_service_cb;
    opts.cb_user = disc;

    ChiakiErrorCode err = chiaki_discovery_service_init(&disc->service, &opts, &g_cw_log);
    if (err != CHIAKI_ERR_SUCCESS)
    {
        free(disc);
        return NULL;
    }
    return disc;
}

CW_EXPORT void cw_discovery_stop(void *ptr)
{
    CwDiscovery *disc = (CwDiscovery *)ptr;
    if (!disc)
        return;
    chiaki_discovery_service_fini(&disc->service);
    free(disc);
}

CW_EXPORT int cw_wakeup(void *disc_ptr, const char *host_addr, const uint8_t *regist_key_16, int is_ps5)
{
    cw_init();
    CwDiscovery *disc = (CwDiscovery *)disc_ptr;
    char key_buf[17];
    memset(key_buf, 0, sizeof(key_buf));
    if (regist_key_16)
        memcpy(key_buf, regist_key_16, 16);
    uint64_t cred = strtoull(key_buf, NULL, 16);
    return (int)chiaki_discovery_wakeup(
        &g_cw_log,
        disc ? &disc->service.discovery : NULL,
        host_addr,
        cred,
        is_ps5 != 0
    );
}

typedef struct cw_regist_t
{
    ChiakiRegist regist;
    CwRegistEventCallback cb;
} CwRegist;

static void cw_regist_cb(ChiakiRegistEvent *event, void *user)
{
    CwRegist *reg = (CwRegist *)user;
    if (!reg || !reg->cb || !event)
        return;

    if (event->type == CHIAKI_REGIST_EVENT_TYPE_FINISHED_SUCCESS && event->registered_host)
    {
        ChiakiRegisteredHost *rh = event->registered_host;
        reg->cb(
            (int)event->type,
            chiaki_target_is_ps5(rh->target) ? 1 : 0,
            rh->server_nickname,
            rh->server_mac,
            (const uint8_t *)rh->rp_regist_key,
            rh->rp_key
        );
    }
    else
    {
        uint8_t empty[16] = { 0 };
        reg->cb((int)event->type, 0, "", empty, empty, empty);
    }
}

CW_EXPORT void *cw_regist_start(const char *host_addr, int broadcast, const uint8_t *psn_account_id_8, uint32_t pin, int target_type, CwRegistEventCallback cb)
{
    cw_init();
    CwRegist *reg = CHIAKI_NEW(CwRegist);
    if (!reg)
        return NULL;
    memset(reg, 0, sizeof(CwRegist));
    reg->cb = cb;

    ChiakiRegistInfo info;
    memset(&info, 0, sizeof(info));
    info.host = host_addr;
    info.broadcast = broadcast != 0;
    info.pin = pin;
    if (target_type == 1)
        info.target = CHIAKI_TARGET_PS5_1;
    else if (target_type == 2)
        info.target = CHIAKI_TARGET_PS4_10;
    else
        info.target = CHIAKI_TARGET_PS4_9;

    if (psn_account_id_8)
        memcpy(info.psn_account_id, psn_account_id_8, CHIAKI_PSN_ACCOUNT_ID_SIZE);

    ChiakiErrorCode err = chiaki_regist_start(&reg->regist, &g_cw_log, &info, cw_regist_cb, reg);
    if (err != CHIAKI_ERR_SUCCESS)
    {
        free(reg);
        return NULL;
    }
    return reg;
}

CW_EXPORT void cw_regist_stop(void *ptr)
{
    CwRegist *reg = (CwRegist *)ptr;
    if (!reg)
        return;
    chiaki_regist_stop(&reg->regist);
    chiaki_regist_fini(&reg->regist);
    free(reg);
}

typedef struct cw_session_t
{
    ChiakiSession session;
    ChiakiOpusDecoder opus_decoder;
    AVCodecContext *codec_ctx;
    struct SwsContext *sws_ctx;
    AVFrame *av_frame;
    AVPacket *av_pkt;
    uint8_t *bgra_buf;
    int bgra_w;
    int bgra_h;
    int bgra_stride;
    uint32_t audio_channels;
    uint32_t audio_rate;
    CwVideoFrameCallback video_cb;
    CwAudioFrameCallback audio_cb;
    CwSessionEventCallback event_cb;
    ChiakiMutex video_mutex;
} CwSession;

static void cw_audio_settings_cb(uint32_t channels, uint32_t rate, void *user)
{
    CwSession *s = (CwSession *)user;
    if (!s)
        return;
    s->audio_channels = channels;
    s->audio_rate = rate;
}

static void cw_audio_frame_cb(int16_t *buf, size_t samples_count, void *user)
{
    CwSession *s = (CwSession *)user;
    if (!s || !s->audio_cb || !buf || samples_count == 0)
        return;
    s->audio_cb(buf, (int)samples_count, (int)(s->audio_channels ? s->audio_channels : 2), (int)(s->audio_rate ? s->audio_rate : 48000));
}

static bool cw_video_sample_cb(uint8_t *buf, size_t buf_size, void *user)
{
    CwSession *s = (CwSession *)user;
    if (!s || !s->codec_ctx || !s->av_pkt || !s->av_frame)
        return false;

    chiaki_mutex_lock(&s->video_mutex);

    av_packet_unref(s->av_pkt);
    s->av_pkt->data = buf;
    s->av_pkt->size = (int)buf_size;

    int r = avcodec_send_packet(s->codec_ctx, s->av_pkt);
    if (r < 0 && r != AVERROR(EAGAIN))
    {
        chiaki_mutex_unlock(&s->video_mutex);
        return false;
    }

    while (avcodec_receive_frame(s->codec_ctx, s->av_frame) == 0)
    {
        int fw = s->av_frame->width;
        int fh = s->av_frame->height;
        if (fw > 0 && fh > 0 && s->video_cb)
        {
            if (s->bgra_w != fw || s->bgra_h != fh || !s->sws_ctx || !s->bgra_buf)
            {
                if (s->sws_ctx)
                    sws_freeContext(s->sws_ctx);
                if (s->bgra_buf)
                    av_free(s->bgra_buf);

                s->bgra_w = fw;
                s->bgra_h = fh;
                s->bgra_stride = fw * 4;
                s->bgra_buf = (uint8_t *)av_malloc((size_t)s->bgra_stride * (size_t)fh);
                s->sws_ctx = sws_getContext(
                    fw, fh, (enum AVPixelFormat)s->av_frame->format,
                    fw, fh, AV_PIX_FMT_BGRA,
                    SWS_FAST_BILINEAR, NULL, NULL, NULL
                );
            }

            if (s->sws_ctx && s->bgra_buf)
            {
                uint8_t *dst_slices[4] = { s->bgra_buf, NULL, NULL, NULL };
                int dst_strides[4] = { s->bgra_stride, 0, 0, 0 };
                sws_scale(
                    s->sws_ctx,
                    (const uint8_t *const *)s->av_frame->data,
                    s->av_frame->linesize,
                    0,
                    fh,
                    dst_slices,
                    dst_strides
                );
                s->video_cb(s->bgra_buf, fw, fh, s->bgra_stride);
            }
        }
        av_frame_unref(s->av_frame);
    }

    chiaki_mutex_unlock(&s->video_mutex);
    return true;
}

static void cw_session_event_cb(ChiakiEvent *event, void *user)
{
    CwSession *s = (CwSession *)user;
    if (!s || !s->event_cb || !event)
        return;

    if (event->type == CHIAKI_EVENT_QUIT)
    {
        s->event_cb((int)event->type, (int)event->quit.reason, event->quit.reason_str ? event->quit.reason_str : "");
    }
    else
    {
        s->event_cb((int)event->type, 0, "");
    }
}

CW_EXPORT void *cw_session_start(
    const char *host_addr,
    const uint8_t *regist_key_16,
    const uint8_t *morning_16,
    int is_ps5,
    int width,
    int height,
    int max_fps,
    int bitrate,
    CwVideoFrameCallback video_cb,
    CwAudioFrameCallback audio_cb,
    CwSessionEventCallback event_cb)
{
    cw_init();

    CwSession *s = CHIAKI_NEW(CwSession);
    if (!s)
        return NULL;
    memset(s, 0, sizeof(CwSession));
    s->video_cb = video_cb;
    s->audio_cb = audio_cb;
    s->event_cb = event_cb;
    chiaki_mutex_init(&s->video_mutex, false);

    enum AVCodecID codec_id = is_ps5 ? AV_CODEC_ID_HEVC : AV_CODEC_ID_H264;
    const AVCodec *codec = avcodec_find_decoder(codec_id);
    if (!codec)
    {
        chiaki_mutex_fini(&s->video_mutex);
        free(s);
        return NULL;
    }

    s->codec_ctx = avcodec_alloc_context3(codec);
    s->codec_ctx->thread_count = 4;
    s->codec_ctx->flags |= AV_CODEC_FLAG_LOW_DELAY;
    if (avcodec_open2(s->codec_ctx, codec, NULL) < 0)
    {
        avcodec_free_context(&s->codec_ctx);
        chiaki_mutex_fini(&s->video_mutex);
        free(s);
        return NULL;
    }

    s->av_frame = av_frame_alloc();
    s->av_pkt = av_packet_alloc();

    chiaki_opus_decoder_init(&s->opus_decoder, &g_cw_log);
    chiaki_opus_decoder_set_cb(&s->opus_decoder, cw_audio_settings_cb, cw_audio_frame_cb, s);

    ChiakiConnectInfo connect_info;
    memset(&connect_info, 0, sizeof(connect_info));
    connect_info.ps5 = is_ps5 != 0;
    connect_info.host = host_addr;
    if (regist_key_16)
        memcpy(connect_info.regist_key, regist_key_16, sizeof(connect_info.regist_key));
    if (morning_16)
        memcpy(connect_info.morning, morning_16, sizeof(connect_info.morning));

    connect_info.video_profile.width = (unsigned int)(width > 0 ? width : 1920);
    connect_info.video_profile.height = (unsigned int)(height > 0 ? height : 1080);
    connect_info.video_profile.max_fps = (unsigned int)(max_fps > 0 ? max_fps : 60);
    connect_info.video_profile.bitrate = (unsigned int)(bitrate > 0 ? bitrate : 15000);
    connect_info.video_profile.codec = is_ps5 ? CHIAKI_CODEC_H265 : CHIAKI_CODEC_H264;
    connect_info.video_profile_auto_downgrade = true;

    ChiakiErrorCode err = chiaki_session_init(&s->session, &connect_info, &g_cw_log);
    if (err != CHIAKI_ERR_SUCCESS)
    {
        chiaki_opus_decoder_fini(&s->opus_decoder);
        av_packet_free(&s->av_pkt);
        av_frame_free(&s->av_frame);
        avcodec_free_context(&s->codec_ctx);
        chiaki_mutex_fini(&s->video_mutex);
        free(s);
        return NULL;
    }

    chiaki_session_set_event_cb(&s->session, cw_session_event_cb, s);
    chiaki_session_set_video_sample_cb(&s->session, cw_video_sample_cb, s);

    ChiakiAudioSink audio_sink;
    chiaki_opus_decoder_get_sink(&s->opus_decoder, &audio_sink);
    chiaki_session_set_audio_sink(&s->session, &audio_sink);

    err = chiaki_session_start(&s->session);
    if (err != CHIAKI_ERR_SUCCESS)
    {
        chiaki_session_fini(&s->session);
        chiaki_opus_decoder_fini(&s->opus_decoder);
        av_packet_free(&s->av_pkt);
        av_frame_free(&s->av_frame);
        avcodec_free_context(&s->codec_ctx);
        chiaki_mutex_fini(&s->video_mutex);
        free(s);
        return NULL;
    }

    return s;
}

CW_EXPORT void cw_session_set_controller(
    void *ptr,
    uint32_t buttons,
    uint8_t l2_state,
    uint8_t r2_state,
    int16_t left_x,
    int16_t left_y,
    int16_t right_x,
    int16_t right_y)
{
    CwSession *s = (CwSession *)ptr;
    if (!s)
        return;

    ChiakiControllerState state;
    chiaki_controller_state_set_idle(&state);
    state.buttons = buttons;
    state.l2_state = l2_state;
    state.r2_state = r2_state;
    state.left_x = left_x;
    state.left_y = left_y;
    state.right_x = right_x;
    state.right_y = right_y;
    chiaki_session_set_controller_state(&s->session, &state);
}

CW_EXPORT void cw_session_set_login_pin(void *ptr, const char *pin)
{
    CwSession *s = (CwSession *)ptr;
    if (!s || !pin)
        return;
    chiaki_session_set_login_pin(&s->session, (const uint8_t *)pin, strlen(pin));
}

CW_EXPORT void cw_session_stop(void *ptr)
{
    CwSession *s = (CwSession *)ptr;
    if (!s)
        return;

    chiaki_session_stop(&s->session);
    chiaki_session_join(&s->session);
    chiaki_session_fini(&s->session);
    chiaki_opus_decoder_fini(&s->opus_decoder);

    chiaki_mutex_lock(&s->video_mutex);
    if (s->sws_ctx)
        sws_freeContext(s->sws_ctx);
    if (s->bgra_buf)
        av_free(s->bgra_buf);
    if (s->av_pkt)
        av_packet_free(&s->av_pkt);
    if (s->av_frame)
        av_frame_free(&s->av_frame);
    if (s->codec_ctx)
        avcodec_free_context(&s->codec_ctx);
    chiaki_mutex_unlock(&s->video_mutex);

    chiaki_mutex_fini(&s->video_mutex);
    free(s);
}
