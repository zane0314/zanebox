#!/usr/bin/env python3
"""Controlled HTTP and SOCKS5 exits; no internet, credentials or third-party nodes."""
import argparse, json, socket, socketserver, threading, struct, time, select
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
p=argparse.ArgumentParser();p.add_argument('--log',required=True);p.add_argument('--delay-a-ms',type=int,default=0);p.add_argument('--delay-b-ms',type=int,default=0);a=p.parse_args()
assert 0<=a.delay_a_ms<=10000 and 0<=a.delay_b_ms<=10000
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
    def read(self,n,connection=None):
        b=b''
        while len(b)<n:
            part=(connection or self.request).recv(n-len(b))
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
            if port==19087:
                record(self.server.marker+'-DNS',f'{host}:{port}')
                target=19088 if self.server.marker=='EXIT_B' else 19089
                with socket.create_connection(('127.0.0.1',target),timeout=15) as remote:
                    size=self.read(2);body=self.read(struct.unpack('!H',size)[0]);remote.sendall(size+body)
                    header=self.read(2,remote);length=struct.unpack('!H',header)[0];answer=self.read(length,remote)
                    self.request.sendall(header+answer)
                return
            b=b''
            while b'\r\n\r\n' not in b:b+=self.read(1)
            marker=self.server.marker;record(marker,f'{host}:{port}')
            path=b.split(b' ',2)[1].decode()
            if path.startswith('/test'):time.sleep((a.delay_a_ms if marker=='EXIT_A' else a.delay_b_ms)/1000)
            if '/hang' in path:time.sleep(12)
            if '/trace' in path:marker='ip='+('203.0.113.10' if marker=='EXIT_A' else '203.0.113.11')+'\n'
            self.request.sendall(response(marker,b'x'*262144 if '/speed' in path else None))
        except (EOFError,OSError,ValueError,AssertionError):pass
class Server(socketserver.ThreadingTCPServer):allow_reuse_address=True;daemon_threads=True
for port,marker in [(19081,'EXIT_A'),(19082,'EXIT_B')]:
    s=Server(('0.0.0.0',port),SOCKS);s.marker=marker;threading.Thread(target=s.serve_forever,daemon=True).start()
# The answer identifies direct/A/B DNS paths; only controlled emulator addresses are returned.
class DNS(SOCKS):
    def handle(self):
        self.request.settimeout(15)
        try:
            query=self.read(struct.unpack('!H',self.read(2))[0]);end=12;labels=[]
            while query[end]:
                size=query[end];labels.append(query[end+1:end+1+size].decode());end+=size+1
            end+=1;qtype,qclass=struct.unpack('!HH',query[end:end+4]);question=query[12:end+4]
            record('DNS-'+self.server.marker,'.'.join(labels)+':'+str(qtype))
            answer=b''
            if qtype==1 and qclass==1:answer=b'\xc0\x0c'+struct.pack('!HHIH',1,1,60,4)+socket.inet_aton(self.server.address)
            response=query[:2]+struct.pack('!HHHHH',0x8180,1,int(bool(answer)),0,0)+question+answer
            self.request.sendall(struct.pack('!H',len(response))+response)
        except (EOFError,OSError,ValueError,IndexError,struct.error):pass
for port,marker,address in [(19087,'DIRECT','10.0.2.2'),(19088,'B','10.0.2.3'),(19089,'A','10.0.2.4')]:
    server=Server(('0.0.0.0',port),DNS);server.marker=marker;server.address=address
    threading.Thread(target=server.serve_forever,daemon=True).start()
# Real forwarding hops for chain/front/landing acceptance, restricted to these fixture ports.
class ForwardSOCKS(SOCKS):
    def handle(self):
        self.request.settimeout(15)
        try:
            _,n=self.read(2);self.read(n);self.request.sendall(b'\x05\x00')
            _,cmd,_,typ=self.read(4)
            if typ==1:host='.'.join(map(str,self.read(4)))
            elif typ==3:host=self.read(self.read(1)[0]).decode()
            else:raise ValueError(typ)
            port=struct.unpack('!H',self.read(2))[0]
            assert cmd==1 and host in ('10.0.2.2','127.0.0.1') and port in (19081,19082,19085,19086)
            with socket.create_connection(('127.0.0.1',port),timeout=15) as remote:
                record(self.server.marker,f'{host}:{port}')
                self.request.sendall(b'\x05\x00\x00\x01\x7f\x00\x00\x01\x00\x00')
                while True:
                    ready,_,_=select.select([self.request,remote],[],[],15)
                    if not ready:return
                    for source in ready:
                        body=source.recv(65536)
                        if not body:return
                        (remote if source is self.request else self.request).sendall(body)
        except (EOFError,OSError,ValueError,AssertionError):pass
for port,marker in [(19085,'FRONT'),(19086,'MIDDLE')]:
    server=Server(('0.0.0.0',port),ForwardSOCKS);server.marker=marker
    threading.Thread(target=server.serve_forever,daemon=True).start()
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
