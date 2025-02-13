package dev.amine.SNMP;
import org.snmp4j.*;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.*;
import org.snmp4j.transport.DefaultUdpTransportMapping;

import java.io.IOException;

public class SnmpManager implements AutoCloseable {
    private final Snmp snmp;
    private final Address targetAddress;
    private final String community;
    public SnmpManager(String address, String community) throws IOException {
        this.community=community;
        this.targetAddress=new UdpAddress(address+"/161");
        TransportMapping transport= new DefaultUdpTransportMapping();
        this.snmp=new Snmp(transport);
        transport.listen();
    }
    public String getAsString(String oid) throws IOException {
        ResponseEvent event=get(new OID[]{new OID(oid)});
        return extractResponceValue(event);
    }
    private ResponseEvent get(OID[] oids)throws IOException{
        PDU pdu=new PDU();
        for (OID oid:oids){
            pdu.add(new VariableBinding(oid));
        }
        pdu.setType(PDU.GET);
        return snmp.send(pdu,createTarget(),null);
    }
    private Target createTarget(){
        CommunityTarget target=new CommunityTarget();
        target.setCommunity(new OctetString(community));
        target.setAddress(targetAddress);
        target.setRetries(2);
        target.setTimeout(1500);
        target.setVersion(SnmpConstants.version2c);
        return target;
    }
    private String extractResponceValue(ResponseEvent event){
        if(event!= null && event.getResponse()!=null){
            for(VariableBinding vb : event.getResponse().getVariableBindings()){
                return vb.getVariable().toString();
            }
        }
        return null;
    }
    @Override
    public void close() throws IOException {
        snmp.close();
    }
}
