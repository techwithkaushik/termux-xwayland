#include "xw11_input_dispatcher.h"

#include <errno.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <unistd.h>

struct xw11_input_dispatcher {
    int fd;
    uint32_t session_id;
    uint64_t next_sequence;
    xw11_input_queue *queue;
    pthread_t thread;
    pthread_mutex_t mutex;
    pthread_cond_t condition;
    int mutex_initialized;
    int condition_initialized;
    int started;
    int stop_requested;
    int thread_joined;
    xw11_input_dispatcher_result last_error;
};

static void set_error_locked(xw11_input_dispatcher *dispatcher,
                             xw11_input_dispatcher_result error) {
    if (dispatcher->last_error == XW11_INPUT_DISPATCHER_OK) {
        dispatcher->last_error = error;
    }
    dispatcher->stop_requested = 1;
    pthread_cond_broadcast(&dispatcher->condition);
}

static void *dispatch_main(void *opaque) {
    xw11_input_dispatcher *dispatcher =
        static_cast<xw11_input_dispatcher *>(opaque);

    for (;;) {
        pthread_mutex_lock(&dispatcher->mutex);
        const int stopping = dispatcher->stop_requested;
        pthread_mutex_unlock(&dispatcher->mutex);
        if (stopping) {
            break;
        }

        xw11_input_event event{};
        const xw11_input_queue_result popped =
            xw11_input_queue_pop(dispatcher->queue, &event);
        if (popped == XW11_INPUT_QUEUE_ERR_EMPTY) {
            pthread_mutex_lock(&dispatcher->mutex);
            if (!dispatcher->stop_requested &&
                xw11_input_queue_size(dispatcher->queue) == 0) {
                pthread_cond_wait(&dispatcher->condition, &dispatcher->mutex);
            }
            pthread_mutex_unlock(&dispatcher->mutex);
            continue;
        }
        if (popped != XW11_INPUT_QUEUE_OK) {
            pthread_mutex_lock(&dispatcher->mutex);
            set_error_locked(dispatcher, XW11_INPUT_DISPATCHER_ERR_QUEUE);
            pthread_mutex_unlock(&dispatcher->mutex);
            break;
        }

        const xw11_input_transport_result sent = xw11_send_input_event(
            dispatcher->fd, dispatcher->session_id,
            &dispatcher->next_sequence, &event);
        if (sent != XW11_INPUT_TRANSPORT_OK) {
            pthread_mutex_lock(&dispatcher->mutex);
            set_error_locked(dispatcher, XW11_INPUT_DISPATCHER_ERR_TRANSPORT);
            pthread_mutex_unlock(&dispatcher->mutex);
            shutdown(dispatcher->fd, SHUT_RDWR);
            break;
        }
    }
    return nullptr;
}

extern "C" xw11_input_dispatcher *xw11_input_dispatcher_create(
    int connected_fd, uint32_t session_id) {
    if (connected_fd < 0 || session_id == 0) {
        return nullptr;
    }

    xw11_input_dispatcher *dispatcher =
        static_cast<xw11_input_dispatcher *>(calloc(1, sizeof(*dispatcher)));
    if (dispatcher == nullptr) {
        return nullptr;
    }
    dispatcher->fd = connected_fd;
    dispatcher->session_id = session_id;
    dispatcher->next_sequence = 1;
    dispatcher->last_error = XW11_INPUT_DISPATCHER_OK;
    dispatcher->queue = xw11_input_queue_create();
    if (dispatcher->queue == nullptr) {
        free(dispatcher);
        return nullptr;
    }
    if (pthread_mutex_init(&dispatcher->mutex, nullptr) != 0) {
        xw11_input_queue_destroy(dispatcher->queue);
        free(dispatcher);
        return nullptr;
    }
    dispatcher->mutex_initialized = 1;
    if (pthread_cond_init(&dispatcher->condition, nullptr) != 0) {
        pthread_mutex_destroy(&dispatcher->mutex);
        xw11_input_queue_destroy(dispatcher->queue);
        free(dispatcher);
        return nullptr;
    }
    dispatcher->condition_initialized = 1;
    return dispatcher;
}

extern "C" xw11_input_dispatcher_result xw11_input_dispatcher_start(
    xw11_input_dispatcher *dispatcher) {
    if (dispatcher == nullptr) {
        return XW11_INPUT_DISPATCHER_ERR_ARGUMENT;
    }
    pthread_mutex_lock(&dispatcher->mutex);
    if (dispatcher->started || dispatcher->stop_requested) {
        pthread_mutex_unlock(&dispatcher->mutex);
        return XW11_INPUT_DISPATCHER_ERR_STATE;
    }
    dispatcher->started = 1;
    pthread_mutex_unlock(&dispatcher->mutex);

    if (pthread_create(&dispatcher->thread, nullptr, dispatch_main,
                       dispatcher) != 0) {
        pthread_mutex_lock(&dispatcher->mutex);
        dispatcher->started = 0;
        set_error_locked(dispatcher, XW11_INPUT_DISPATCHER_ERR_THREAD);
        pthread_mutex_unlock(&dispatcher->mutex);
        return XW11_INPUT_DISPATCHER_ERR_THREAD;
    }
    return XW11_INPUT_DISPATCHER_OK;
}

extern "C" xw11_input_dispatcher_result xw11_input_dispatcher_submit(
    xw11_input_dispatcher *dispatcher, const xw11_input_event *event) {
    if (dispatcher == nullptr || event == nullptr) {
        return XW11_INPUT_DISPATCHER_ERR_ARGUMENT;
    }
    pthread_mutex_lock(&dispatcher->mutex);
    if (!dispatcher->started || dispatcher->stop_requested) {
        const xw11_input_dispatcher_result error =
            dispatcher->last_error == XW11_INPUT_DISPATCHER_OK
                ? XW11_INPUT_DISPATCHER_ERR_STATE
                : dispatcher->last_error;
        pthread_mutex_unlock(&dispatcher->mutex);
        return error;
    }
    const xw11_input_queue_result queued =
        xw11_input_queue_push(dispatcher->queue, event);
    if (queued != XW11_INPUT_QUEUE_OK) {
        pthread_mutex_unlock(&dispatcher->mutex);
        return XW11_INPUT_DISPATCHER_ERR_QUEUE;
    }
    pthread_cond_signal(&dispatcher->condition);
    pthread_mutex_unlock(&dispatcher->mutex);
    return XW11_INPUT_DISPATCHER_OK;
}

extern "C" void xw11_input_dispatcher_stop(
    xw11_input_dispatcher *dispatcher) {
    if (dispatcher == nullptr) {
        return;
    }
    pthread_mutex_lock(&dispatcher->mutex);
    dispatcher->stop_requested = 1;
    pthread_cond_broadcast(&dispatcher->condition);
    const int should_join = dispatcher->started && !dispatcher->thread_joined;
    pthread_mutex_unlock(&dispatcher->mutex);

    shutdown(dispatcher->fd, SHUT_RDWR);
    if (should_join && pthread_join(dispatcher->thread, nullptr) == 0) {
        pthread_mutex_lock(&dispatcher->mutex);
        dispatcher->thread_joined = 1;
        pthread_mutex_unlock(&dispatcher->mutex);
    }
}

extern "C" void xw11_input_dispatcher_destroy(
    xw11_input_dispatcher *dispatcher) {
    if (dispatcher == nullptr) {
        return;
    }
    xw11_input_dispatcher_stop(dispatcher);
    close(dispatcher->fd);
    xw11_input_queue_destroy(dispatcher->queue);
    if (dispatcher->condition_initialized) {
        pthread_cond_destroy(&dispatcher->condition);
    }
    if (dispatcher->mutex_initialized) {
        pthread_mutex_destroy(&dispatcher->mutex);
    }
    free(dispatcher);
}

extern "C" xw11_input_dispatcher_result xw11_input_dispatcher_last_error(
    xw11_input_dispatcher *dispatcher) {
    if (dispatcher == nullptr) {
        return XW11_INPUT_DISPATCHER_ERR_ARGUMENT;
    }
    pthread_mutex_lock(&dispatcher->mutex);
    const xw11_input_dispatcher_result error = dispatcher->last_error;
    pthread_mutex_unlock(&dispatcher->mutex);
    return error;
}

extern "C" size_t xw11_input_dispatcher_queue_size(
    xw11_input_dispatcher *dispatcher) {
    return dispatcher == nullptr ? 0 : xw11_input_queue_size(dispatcher->queue);
}

extern "C" uint64_t xw11_input_dispatcher_dropped_motion_count(
    xw11_input_dispatcher *dispatcher) {
    return dispatcher == nullptr ? 0 :
        xw11_input_queue_dropped_motion_count(dispatcher->queue);
}
