package cn.ybcase.bureau.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;

/** Literal-IP proxy allowlist. Never resolves names or trusts headers from an unlisted peer. */
@Component
public class AuditClientAddress {
    private record Network(byte[] address,int prefix) {
        boolean contains(byte[] other) {
            if(other==null || other.length!=address.length)return false;
            for(int bit=0;bit<prefix;bit++)if((address[bit/8] & (128>>(bit%8)))!=(other[bit/8] & (128>>(bit%8))))return false;
            return true;
        }
    }
    private final List<Network> trusted;
    public AuditClientAddress(@Value("${ybcase.audit.trusted-proxies:}") String configured) {
        var networks=new ArrayList<Network>();
        if(configured!=null&&!configured.isBlank())for(String entry:configured.split(",",-1)){
            String[] parts=entry.trim().split("/",-1);byte[] address=parse(parts[0]);
            if(address==null||parts.length>2)throw new IllegalArgumentException("Invalid audit trusted proxy IP/CIDR");
            int prefix=address.length*8;
            if(parts.length==2){try{prefix=Integer.parseInt(parts[1]);}catch(NumberFormatException e){throw new IllegalArgumentException("Invalid audit trusted proxy prefix");}}
            if(prefix<0||prefix>address.length*8)throw new IllegalArgumentException("Invalid audit trusted proxy prefix");
            networks.add(new Network(address,prefix));
        }
        trusted=List.copyOf(networks);
    }
    private boolean trusted(String ip){byte[] address=parse(ip);return trusted.stream().anyMatch(n->n.contains(address));}
    public String resolve(HttpServletRequest request) {
        String remote=request.getRemoteAddr();
        if(!trusted(remote))return remote;
        var values=Collections.list(request.getHeaders("X-Forwarded-For"));
        String xff=String.join(",",values);
        if(!xff.isBlank()){
            if(xff.length()>8192)return remote;
            String[] hops=xff.split(",",-1);if(hops.length>64)return remote;
            for(int i=0;i<hops.length;i++){hops[i]=hops[i].trim();if(parse(hops[i])==null)return remote;}
            String current=remote;
            for(int i=hops.length-1;i>=0;i--){if(!trusted(current))return current;current=hops[i];}
            return current;
        }
        String real=request.getHeader("X-Real-IP");
        return real!=null&&parse(real.trim())!=null?real.trim():remote;
    }
    /** Strict IPv4/IPv6 parser, including IPv4 tails; deliberately rejects names, ports and zones. */
    private static byte[] parse(String text) {
        if(text==null||text.isEmpty()||text.length()>45)return null;
        if(!text.contains(":"))return ipv4(text);
        if(!text.matches("[0-9A-Fa-f:.]+"))return null;
        int compressed=text.indexOf("::");
        if(compressed>=0&&text.indexOf("::",compressed+2)>=0)return null;
        String[] halves=compressed<0?new String[]{text}:new String[]{text.substring(0,compressed),text.substring(compressed+2)};
        List<Integer> left=groups(halves[0]),right=halves.length==2?groups(halves[1]):List.of();
        if(left==null||right==null)return null;
        int total=left.size()+right.size();
        if(compressed<0?total!=8:total>=8)return null;
        // A dotted tail must be the final 32 bits, never before a compressed suffix.
        if(text.contains(".") && (text.endsWith(":") || (compressed>=0&&halves[0].contains("."))))return null;
        byte[] result=new byte[16];int at=0;
        for(int value:left){result[at++]=(byte)(value>>8);result[at++]=(byte)value;}
        at=16-right.size()*2;
        for(int value:right){result[at++]=(byte)(value>>8);result[at++]=(byte)value;}
        return result;
    }
    private static List<Integer> groups(String text) {
        if(text.isEmpty())return List.of();var values=new ArrayList<Integer>();String[] groups=text.split(":",-1);
        for(int i=0;i<groups.length;i++){
            String part=groups[i];
            if(part.contains(".")){
                byte[] v=ipv4(part);if(v==null||i!=groups.length-1)return null;
                values.add((v[0]&255)*256+(v[1]&255));values.add((v[2]&255)*256+(v[3]&255));
            }else{if(!part.matches("[0-9A-Fa-f]{1,4}"))return null;values.add(Integer.parseInt(part,16));}
        }
        return values;
    }
    private static byte[] ipv4(String text) {
        String[] octets=text.split("\\.",-1);if(octets.length!=4)return null;byte[] result=new byte[4];
        for(int i=0;i<4;i++){
            if(!octets[i].matches("0|[1-9][0-9]{0,2}"))return null;
            int value=Integer.parseInt(octets[i]);if(value>255)return null;result[i]=(byte)value;
        }
        return result;
    }
}
