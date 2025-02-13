package dev.amine;

import dev.amine.SNMP.PrinterData;
import dev.amine.SNMP.PrinterDetector;

import java.util.List;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
    public static void main(String[] args) {
        String subnetBase="192.168.1";
        PrinterDetector detector=new PrinterDetector();

        try {
            List<PrinterData> printers = detector.scanNetwork(subnetBase);
            for (PrinterData printer : printers) {
                System.out.println(printer);
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}