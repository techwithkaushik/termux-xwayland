#include "xw11_input_queue.h"

#include <pthread.h>
#include <stdlib.h>
#include <string.h>

struct xw11_input_queue {
    pthread_mutex_t mutex;
    xw11_input_event events[XW11_INPUT_QUEUE_CAPACITY];
    size_t head;
    size_t count;
    uint64_t dropped_motion;
};

namespace {
bool motion(const xw11_input_event &e) {
    return e.type == XW11_INPUT_POINTER_MOTION ||
           (e.type == XW11_INPUT_TOUCH && e.action == XW11_INPUT_MOVE);
}
size_t at(const xw11_input_queue *q, size_t i) {
    return (q->head + i) % XW11_INPUT_QUEUE_CAPACITY;
}
void erase_at(xw11_input_queue *q, size_t i) {
    for (size_t n = i; n + 1 < q->count; ++n)
        q->events[at(q, n)] = q->events[at(q, n + 1)];
    --q->count;
}
}  // namespace

extern "C" xw11_input_queue *xw11_input_queue_create(void) {
    xw11_input_queue *q = static_cast<xw11_input_queue *>(calloc(1, sizeof(*q)));
    if (q == nullptr)
        return nullptr;
    if (pthread_mutex_init(&q->mutex, nullptr) != 0) {
        free(q);
        return nullptr;
    }
    return q;
}

extern "C" void xw11_input_queue_destroy(xw11_input_queue *q) {
    if (q == nullptr)
        return;
    pthread_mutex_destroy(&q->mutex);
    free(q);
}

extern "C" xw11_input_queue_result xw11_input_queue_push(
    xw11_input_queue *q, const xw11_input_event *e) {
    if (q == nullptr || e == nullptr)
        return XW11_INPUT_QUEUE_ERR_ARGUMENT;
    uint8_t wire[XW11_INPUT_EVENT_WIRE_SIZE];
    if (xw11_input_event_encode(e, wire, sizeof(wire)) != XW11_INPUT_EVENT_OK)
        return XW11_INPUT_QUEUE_ERR_ARGUMENT;

    pthread_mutex_lock(&q->mutex);
    if (motion(*e) && q->count != 0) {
        xw11_input_event *last = &q->events[at(q, q->count - 1)];
        if (motion(*last) && last->type == e->type &&
            last->surface_generation == e->surface_generation &&
            last->device_id == e->device_id) {
            *last = *e;
            pthread_mutex_unlock(&q->mutex);
            return XW11_INPUT_QUEUE_OK;
        }
    }

    if (q->count == XW11_INPUT_QUEUE_CAPACITY) {
        if (motion(*e)) {
            ++q->dropped_motion;
            pthread_mutex_unlock(&q->mutex);
            return XW11_INPUT_QUEUE_ERR_FULL;
        }
        size_t found = q->count;
        for (size_t i = 0; i < q->count; ++i) {
            if (motion(q->events[at(q, i)])) {
                found = i;
                break;
            }
        }
        if (found == q->count) {
            pthread_mutex_unlock(&q->mutex);
            return XW11_INPUT_QUEUE_ERR_FULL;
        }
        erase_at(q, found);
        ++q->dropped_motion;
    }
    q->events[at(q, q->count)] = *e;
    ++q->count;
    pthread_mutex_unlock(&q->mutex);
    return XW11_INPUT_QUEUE_OK;
}

extern "C" xw11_input_queue_result xw11_input_queue_pop(
    xw11_input_queue *q, xw11_input_event *e) {
    if (q == nullptr || e == nullptr)
        return XW11_INPUT_QUEUE_ERR_ARGUMENT;
    pthread_mutex_lock(&q->mutex);
    if (q->count == 0) {
        pthread_mutex_unlock(&q->mutex);
        return XW11_INPUT_QUEUE_ERR_EMPTY;
    }
    *e = q->events[q->head];
    q->head = (q->head + 1) % XW11_INPUT_QUEUE_CAPACITY;
    --q->count;
    pthread_mutex_unlock(&q->mutex);
    return XW11_INPUT_QUEUE_OK;
}

extern "C" size_t xw11_input_queue_size(xw11_input_queue *q) {
    if (q == nullptr)
        return 0;
    pthread_mutex_lock(&q->mutex);
    const size_t result = q->count;
    pthread_mutex_unlock(&q->mutex);
    return result;
}

extern "C" uint64_t xw11_input_queue_dropped_motion_count(xw11_input_queue *q) {
    if (q == nullptr)
        return 0;
    pthread_mutex_lock(&q->mutex);
    const uint64_t result = q->dropped_motion;
    pthread_mutex_unlock(&q->mutex);
    return result;
}
