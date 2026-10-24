#include "xw11_input_transport.h"
#include <assert.h>
#include <stdio.h>
#include <sys/socket.h>
#include <unistd.h>
int main() {
 int fds[2]; assert(socketpair(AF_UNIX,SOCK_STREAM,0,fds)==0);
 xw11_input_event e{}; e.surface_generation=7; e.monotonic_time_ns=99; e.type=XW11_INPUT_POINTER_BUTTON; e.action=XW11_INPUT_DOWN; e.code=1; e.x=12; e.y=34;
 uint64_t sequence=1;
 assert(xw11_send_input_event(fds[0],42,&sequence,&e)==XW11_INPUT_TRANSPORT_OK);
 assert(sequence==2);
 uint8_t payload[XW11_INPUT_EVENT_WIRE_SIZE]; xw11_frame_header header{};
 assert(xw11_receive_frame(fds[1],&header,payload,sizeof(payload))==XW11_IO_OK);
 assert(header.type==XW11_MSG_INPUT_EVENT&&header.session_id==42&&header.sequence_id==1&&header.payload_length==sizeof(payload));
 xw11_input_event out{}; assert(xw11_input_event_decode(payload,sizeof(payload),&out)==XW11_INPUT_EVENT_OK);
 assert(out.surface_generation==7&&out.action==XW11_INPUT_DOWN&&out.x==12&&out.y==34);
 assert(xw11_send_input_event(-1,42,&sequence,&e)==XW11_INPUT_TRANSPORT_ERR_ARGUMENT);
 uint64_t exhausted=UINT64_MAX; assert(xw11_send_input_event(fds[0],42,&exhausted,&e)==XW11_INPUT_TRANSPORT_ERR_SEQUENCE);
 close(fds[0]); close(fds[1]); puts("XW11 input transport tests passed");
}
