package com.example.report.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.InetAddress;
import java.util.*;

/** Forwarded addresses are accepted only through explicitly trusted numeric IP/CIDR hops. */
@Component
public class ClientAddressResolver {
    private final List<Network> trusted;
    public ClientAddressResolver(@Value("${web.trusted-proxies:}") String proxies) {
        trusted=Arrays.stream(proxies.split(",")).map(String::trim).filter(s->!s.isEmpty()).map(Network::parse).toList();
    }
    public String resolve(HttpServletRequest request) {
        String peer=request.getRemoteAddr();
        byte[] address=parseAddress(peer);
        if(address==null || !isTrusted(address)) return peer;
        String header=request.getHeader("X-Forwarded-For");
        if(header==null || header.length()>4096) return peer;
        String[] hops=header.split(",",-1);
        if(hops.length>32) return peer;
        List<byte[]> parsed=new ArrayList<>();
        for(String hop:hops) {
            byte[] value=parseAddress(hop.trim());
            if(value==null) return peer;
            parsed.add(value);
        }
        // Walk backwards: the first untrusted hop is the client. Ignore any spoofed prefix.
        for(int i=parsed.size()-1;i>=0 && isTrusted(address);i--) address=parsed.get(i);
        try {return InetAddress.getByAddress(address).getHostAddress();}
        catch(Exception impossible){throw new IllegalStateException(impossible);}
    }
    private boolean isTrusted(byte[] address) {return trusted.stream().anyMatch(n->n.contains(address));}
    private static byte[] parseAddress(String value) {
        if(value==null || value.isBlank() || value.length()>45) return null;
        try {
            if(value.contains(":")) {
                if(!value.matches("[0-9a-fA-F:.]+")) return null;
            } else {
                String[] parts=value.split("\\.",-1);
                if(parts.length!=4) return null;
                for(String part:parts) if(!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part)>255) return null;
            }
            return InetAddress.getByName(value).getAddress();
        } catch(Exception invalid){return null;}
    }
    private record Network(byte[] address,int bits) {
        static Network parse(String value) {
            String[] parts=value.split("/",-1);
            byte[] address=parseAddress(parts[0]);
            if(parts.length>2 || address==null) throw new IllegalArgumentException("trusted-proxies 必须为 IP 或 CIDR: "+value);
            int bits=parts.length==1?address.length*8:Integer.parseInt(parts[1]);
            if(bits<0 || bits>address.length*8) throw new IllegalArgumentException("trusted-proxies CIDR 无效: "+value);
            return new Network(address,bits);
        }
        boolean contains(byte[] other) {
            if(other.length!=address.length) return false;
            for(int i=0;i<bits;i++) {
                int mask=1<<(7-i%8);
                if((address[i/8]&mask)!=(other[i/8]&mask)) return false;
            }
            return true;
        }
    }
}
