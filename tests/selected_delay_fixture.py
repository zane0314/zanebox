"""NodeSwitchLatencyTest fixture: keep-alive HTTP/SOCKS, 500ms handshake, 30ms request.

Run alongside tests/network_fixture.py on the host; only local test traffic is forwarded.
"""
import argparse
import http.server
import json
import socket
import socketserver
import struct
import threading
import time
from pathlib import Path

p=argparse.ArgumentParser();p.add_argument('--log',required=True);args=p.parse_args()
lock=threading.Lock()
def record(value):
    with lock,Path(args.log).open('a') as f:f.write(json.dumps(dict(value,time=time.time()))+'\n')
class HTTP(http.server.BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    def do_GET(self):
        record({'request':self.path,'connection':str(self.client_address)})
        time.sleep(.03)
        self.send_response(204);self.send_header('Content-Length','0');self.end_headers()
    def log_message(self,*args):pass
class SOCKS(socketserver.BaseRequestHandler):
    def read(self,n):
        result=b''
        while len(result)<n:
            chunk=self.request.recv(n-len(result))
            if not chunk:raise EOFError()
            result+=chunk
        return result
    def handle(self):
        self.request.settimeout(10);remote=None
        try:
            _,n=self.read(2);self.read(n);time.sleep(.5);self.request.sendall(b'\x05\x00')
            _,cmd,_,kind=self.read(4)
            if kind==1:host=socket.inet_ntoa(self.read(4))
            elif kind==3:host=self.read(self.read(1)[0]).decode()
            else:raise ValueError(kind)
            port=struct.unpack('!H',self.read(2))[0]
            assert cmd==1 and port==19093
            record({'dial':host,'port':port})
            remote=socket.create_connection(('127.0.0.1',19093),timeout=10)
            self.request.sendall(b'\x05\x00\x00\x01\x7f\x00\x00\x01\x00\x00')
            def pump(src,dest):
                try:
                    while data:=src.recv(65536):dest.sendall(data)
                except OSError:pass
                finally:
                    try:dest.shutdown(socket.SHUT_WR)
                    except OSError:pass
            worker=threading.Thread(target=pump,args=(remote,self.request),daemon=True);worker.start();pump(self.request,remote);worker.join(1)
        except (OSError,EOFError,ValueError,AssertionError):pass
        finally:
            if remote:remote.close()
class Server(socketserver.ThreadingTCPServer):allow_reuse_address=True;daemon_threads=True
threading.Thread(target=http.server.ThreadingHTTPServer(('127.0.0.1',19093),HTTP).serve_forever,daemon=True).start()
print('SELECTED_DELAY_FIXTURE_READY',flush=True)
Server(('127.0.0.1',19092),SOCKS).serve_forever()
