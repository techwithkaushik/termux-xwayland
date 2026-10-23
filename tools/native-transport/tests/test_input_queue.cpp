#include "xw11_input_queue.h"
#include <assert.h>
#include <stdio.h>
static xw11_input_event ev(uint16_t type,uint16_t action,uint32_t code,int32_t x,uint64_t gen=1) { xw11_input_event e{}; e.surface_generation=gen; e.monotonic_time_ns=code+100; e.type=type; e.action=action; e.code=code; e.x=x; e.y=x+1; return e; }
int main() {
 auto *q=xw11_input_queue_create(); assert(q);
 auto a=ev(XW11_INPUT_POINTER_MOTION,XW11_INPUT_MOVE,0,10), b=ev(XW11_INPUT_POINTER_MOTION,XW11_INPUT_MOVE,0,20); 
 assert(xw11_input_queue_push(q,&a)==XW11_INPUT_QUEUE_OK); assert(xw11_input_queue_push(q,&b)==XW11_INPUT_QUEUE_OK); assert(xw11_input_queue_size(q)==1);
 xw11_input_event out{}; assert(xw11_input_queue_pop(q,&out)==XW11_INPUT_QUEUE_OK&&out.x==20); assert(xw11_input_queue_pop(q,&out)==XW11_INPUT_QUEUE_ERR_EMPTY);
 for(size_t i=0;i<XW11_INPUT_QUEUE_CAPACITY;++i) { auto k=ev(XW11_INPUT_KEY,(i%2)?XW11_INPUT_UP:XW11_INPUT_DOWN,(uint32_t)i+1,0); assert(xw11_input_queue_push(q,&k)==XW11_INPUT_QUEUE_OK); }
 auto m=ev(XW11_INPUT_POINTER_MOTION,XW11_INPUT_MOVE,0,50); assert(xw11_input_queue_push(q,&m)==XW11_INPUT_QUEUE_ERR_FULL);
 auto critical=ev(XW11_INPUT_POINTER_BUTTON,XW11_INPUT_DOWN,1,50); assert(xw11_input_queue_push(q,&critical)==XW11_INPUT_QUEUE_ERR_FULL); assert(xw11_input_queue_size(q)==XW11_INPUT_QUEUE_CAPACITY);
 assert(xw11_input_queue_pop(q,&out)==XW11_INPUT_QUEUE_OK&&out.code==1); assert(xw11_input_queue_dropped_motion_count(q)==1);
 assert(xw11_input_queue_push(q,nullptr)==XW11_INPUT_QUEUE_ERR_ARGUMENT); xw11_input_queue_destroy(q); puts("XW11 bounded input queue tests passed");
}
