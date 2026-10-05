#!/usr/bin/env python3
"""Controlled HTTP and SOCKS5 exits; no internet, credentials or third-party nodes."""
import argparse, json, socket, socketserver, threading, struct, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
p=argparse.ArgumentParser();p.add_argument('--log',required=True);a=p.parse_args()
lock=threading.Lock()
def record(kind, destination, agent=None):
    with lock, open(a.log,'a') as f:f.write(json.dumps({'time':time.time(),'exit':kind,'destination':destination,**({'userAgent':agent} if agent else {})})+'\n')
def response(marker,payload=None):
    body=payload if payload is not None else marker.encode();return b'HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: '+str(len(body)).encode()+b'\r\n\r\n'+body
class HTTP(BaseHTTPRequestHandler):
    def do_GET(self):
        record('DIRECT',self.path,self.headers.get('User-Agent'))
        if self.path.startswith('/options-subscription'):
            body=json.dumps({'outbounds':[{'type':'socks','tag':'fixture A','server':'10.0.2.2','server_port':19081},{'type':'socks','tag':'fixture A duplicate','server':'10.0.2.2','server_port':19081},{'type':'socks','tag':'fixture B','server':'10.0.2.2','server_port':19082}]}).encode()
        elif self.path.startswith('/subscription'):
            body=json.dumps({'outbounds':[{'type':'socks','tag':'fixture A','server':'10.0.2.2','server_port':19081},{'type':'socks','tag':'fixture B','server':'10.0.2.2','server_port':19082}]}).encode()
        elif self.path.startswith('/trace'):body=b'ip=10.0.2.2\n'
        else:body=b'DIRECT'
        self.send_response(200);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
    def log_message(self,*args):pass
class SOCKS(socketserver.BaseRequestHandler):
    def read(self,n):
        b=b''
        while len(b)<n:
            part=self.request.recv(n-len(b))
            if not part:raise EOFError()
            b+=part
        return b
    def handle(self):
        self.request.settimeout(15)
        try:
            v,n=self.read(2);self.read(n);self.request.sendall(b'\x05\x00');v,cmd,_,typ=self.read(4)
            if typ==1:host='.'.join(map(str,self.read(4)))
            elif typ==3:host=self.read(self.read(1)[0]).decode()
            elif typ==4:host=self.read(16).hex()
            else:raise ValueError(typ)
            port=struct.unpack('!H',self.read(2))[0]
            assert cmd==1
            self.request.sendall(b'\x05\x00\x00\x01\x7f\x00\x00\x01\x00\x00')
            b=b''
            while b'\r\n\r\n' not in b:b+=self.read(1)
            marker=self.server.marker;record(marker,f'{host}:{port}')
            path=b.split(b' ',2)[1].decode()
            if '/hang' in path:time.sleep(12)
            if '/trace' in path:marker='ip='+('203.0.113.10' if marker=='EXIT_A' else '203.0.113.11')+'\n'
            self.request.sendall(response(marker,b'x'*262144 if '/speed' in path else None))
        except (EOFError,OSError,ValueError,AssertionError):pass
class Server(socketserver.ThreadingTCPServer):allow_reuse_address=True;daemon_threads=True
for port,marker in [(19081,'EXIT_A'),(19082,'EXIT_B')]:
    s=Server(('0.0.0.0',port),SOCKS);s.marker=marker;threading.Thread(target=s.serve_forever,daemon=True).start()
# Local RFC3489/5389 binding responder; synthetic addresses are test evidence only.
stun_sockets={port:socket.socket(socket.AF_INET,socket.SOCK_DGRAM) for port in (19083,19084)}
for port,s in stun_sockets.items():s.bind(('0.0.0.0',port))
def stun_attribute(kind,ip,port):
    value=struct.pack('!BBH4B',0,1,port,*map(int,ip.split('.')))
    return struct.pack('!HH',kind,len(value))+value
def stun_loop(port):
    s=stun_sockets[port]
    while True:
        packet,source=s.recvfrom(8192)
        if len(packet)<20 or packet[:2]!=b'\x00\x01':continue
        change=0;offset=20
        while offset+4<=len(packet):
            kind,size=struct.unpack('!HH',packet[offset:offset+4]);value=packet[offset+4:offset+4+size]
            if kind==3 and size==4:change=struct.unpack('!I',value)[0]
            offset+=4+((size+3)//4)*4
        reply_port=(19084 if port==19083 else 19083) if change else port
        other=19084 if reply_port==19083 else 19083
        body=stun_attribute(1,'203.0.113.55',45000)+stun_attribute(4,'10.0.2.2',reply_port)+stun_attribute(5,'10.0.2.2',other)+stun_attribute(0x802b,'10.0.2.2',reply_port)+stun_attribute(0x802c,'10.0.2.2',other)
        stun_sockets[reply_port].sendto(struct.pack('!HH',0x0101,len(body))+packet[4:20]+body,source)
        record('STUN',f'{reply_port}/{source[1]}')
for port in stun_sockets:threading.Thread(target=stun_loop,args=(port,),daemon=True).start()
ThreadingHTTPServer(('0.0.0.0',19080),HTTP).serve_forever()
