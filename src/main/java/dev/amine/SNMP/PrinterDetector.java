package dev.amine.SNMP;

import java.io.IOException;
import java.util.ArrayList;
import org.snmp4j.smi.*;
import java.util.List;
import java.util.concurrent.*;

public class PrinterDetector {
    private static final String community="public";
    private static final int threadPoolSize=10;
    private static final int scanTimeout=3;
    public List<PrinterData> scanNetwork(String subnetBase)throws InterruptedException{
        ExecutorService executor =Executors.newFixedThreadPool(threadPoolSize);
        List<Future<PrinterData>> futures= new ArrayList<>();
        List<PrinterData> printers=new ArrayList<>();
        for(int i =1;i<255;i++){
            String ip=subnetBase+"."+i;
            futures.add(executor.submit(()-> checkPrinter(ip)));
        }
        for (Future<PrinterData> future:futures){
            try{
                PrinterData printer= future.get(scanTimeout,TimeUnit.SECONDS);
                if(printer!=null){
                    printers.add(printer);
                }
            }catch(ExecutionException | TimeoutException e){
                System.out.println("Error: "+e.getMessage());
            }
        }
        executor.shutdown();
        return printers;
    }
    private PrinterData checkPrinter(String ip){
        try(SnmpManager snmp =new SnmpManager(ip,community)){
            String sysDescr=snmp.getAsString(OIDConstants.sysDescr);
            if(sysDescr!=null && isPrinter(sysDescr)){
                PrinterData printer=new PrinterData();
                printer.setIpAddress(ip);
                populatePrinterData(snmp,printer);
                return printer;
            }
        }catch (IOException e){
            System.out.println("Error: "+e.getMessage());
        }
        return null;
    }
    private void populatePrinterData(SnmpManager snmp, PrinterData printer) throws IOException {
        printer.setModel(snmp.getAsString(OIDConstants.printerModel));
        printer.setTotalPages(Long.parseLong(snmp.getAsString(OIDConstants.totalPagesCount)));
        String macHex=snmp.getAsString(OIDConstants.macAddress+".1");
        printer.setMacAddress(formatMac(macHex));
        addTonerLevel(snmp,printer,"black",OIDConstants.blackTonerLevel);
        addTonerLevel(snmp,printer,"cyan",OIDConstants.cyanTonerLevel);
        addTonerLevel(snmp,printer,"magenta",OIDConstants.magentaTonerLevel);
        addTonerLevel(snmp,printer,"yellow",OIDConstants.yellowTonerLevel);
    }
    private void addTonerLevel(SnmpManager snmp, PrinterData printer, String color, String oid) throws IOException {
        String Level=snmp.getAsString(oid);
        if(Level!=null){
            printer.getTonerLevels().put(color,Integer.parseInt(Level));
        }
    }
    private String formatMac(String macHex){
        if(macHex==null)return "unknown";
        macHex=macHex.replaceAll("[^0-9A-Fa-f]","");
        StringBuilder mac=new StringBuilder();
        for(int i=0;i<macHex.length();i+=2){
            mac.append(macHex.substring(i,Math.min(i+2,macHex.length())));
            if(i<10) mac.append(":");
        }
        return mac.toString().toUpperCase();
    }
    private boolean isPrinter(String sysDescr){
        String descr=sysDescr.toLowerCase();
        return descr.contains("printer") || descr.contains("mfp") || descr.contains("print");
    }
}
