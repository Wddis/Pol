package com.recovery.sofarmerverify;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

public class MainActivity extends Activity implements View.OnClickListener {

    // ===== Эталоны, извлечённые из test.so (libunity_farmershoot_balders_v3) =====
    static final String EXPECT_BUILDID = "69815f2befe135c2";
    static final long OFF_BUILDID = 0x280L;            // 8 байт build-id внутри NOTE
    static final long OFF_CODE   = 0x5ac7728L;         // код UnityInitialize (file offset)
    static final int  EXPECT_CODE_WORD = 0x7100699f;   // первое слово (LE) = cmp w12,#0x1a
    static final long OFF_RADAR  = 46402888L;          // строка "RadarView"
    static final String EXPECT_RADAR = "RadarView";
    static final long OFF_RADAR2 = 46404335L;          // строка "RadarMapView"
    static final String EXPECT_RADAR2 = "RadarMapView";

    EditText pkgInput;
    TextView status, details;
    LinearLayout root;
    Button findBtn, checkBtn;
    int pad;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        float d = getResources().getDisplayMetrics().density;
        pad = (int)(16*d);
        ScrollView sv = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad,pad,pad,pad);
        sv.addView(root);

        addTitle("Farmer SO Verifier");
        addText("Проверяет libunity.so УСТАНОВЛЕННОЙ игры на совпадение оффсетов и значений, "
              + "извлечённых из вашего test.so.\nЭталонный Build-ID: " + EXPECT_BUILDID
              + "\nБез root, только чтение публичного APK игры.");

        pkgInput = new EditText(this);
        pkgInput.setHint("имя пакета игры, напр. com.balders.farmershoot");
        pkgInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        root.addView(pkgInput);

        findBtn = new Button(this); findBtn.setText("Авто-поиск игры (по Build-ID)");
        findBtn.setOnClickListener(this); root.addView(findBtn);
        checkBtn = new Button(this); checkBtn.setText("Проверить этот пакет");
        checkBtn.setOnClickListener(this); root.addView(checkBtn);

        status = new TextView(this);
        status.setTextSize(26); status.setTypeface(null,Typeface.BOLD);
        status.setPadding(0,pad,0,pad/2);
        root.addView(status);

        details = new TextView(this);
        details.setTextSize(13); details.setTypeface(Typeface.MONOSPACE);
        details.setTextIsSelectable(true);
        root.addView(details);

        setContentView(sv);
    }

    void addTitle(String t){ TextView tv=new TextView(this); tv.setText(t); tv.setTextSize(22);
        tv.setTypeface(null,Typeface.BOLD); root.addView(tv); }
    void addText(String t){ TextView tv=new TextView(this); tv.setText(t);
        tv.setPadding(0,pad/2,0,pad); root.addView(tv); }
    public void onClick(View v){
        if(v==findBtn){ autoFind(); }
        else if(v==checkBtn){
            String p = pkgInput.getText().toString().trim();
            if(p.isEmpty()){ setStatus(null,"Введите имя пакета или нажмите авто-поиск."); return; }
            runCheck(p);
        }
    }

    void setStatus(Boolean ok, String msg){
        if(ok==null){ status.setText(""); }
        else if(ok){ status.setText("✓  Рабочий"); status.setTextColor(Color.parseColor("#1B8A2F")); }
        else { status.setText("✗  Не совпадает"); status.setTextColor(Color.parseColor("#C0241C")); }
        details.setText(msg);
    }

    // ---- найти установленную игру, чья libunity.so имеет нужный Build-ID ----
    void autoFind(){
        PackageManager pm=getPackageManager();
        List<ApplicationInfo> apps;
        try { apps = pm.getInstalledApplications(0); }
        catch(Throwable t){ setStatus(null,"Не удалось получить список приложений: "+t); return; }
        int unity=0; List<String> matches=new ArrayList<String>();
        StringBuilder log=new StringBuilder();
        for(ApplicationInfo ai: apps){
            SoSource s = locate(ai);
            if(s==null) continue;
            unity++;
            try{
                byte[] bid = s.read(OFF_BUILDID,8);
                if(bid!=null && hex(bid).equalsIgnoreCase(EXPECT_BUILDID))
                    matches.add(ai.packageName);
            }catch(Throwable ignored){}
            finally{ s.close(); }
        }
        if(matches.isEmpty()){
            setStatus(null,"Авто-поиск завершён.\nUnity-игр с libunity.so (arm64): "+unity
                +"\nС Build-ID "+EXPECT_BUILDID+": 0\n\nВведите пакет вручную и нажмите «Проверить».");
        } else {
            pkgInput.setText(matches.get(0));
            runCheck(matches.get(0));
        }
    }

    void runCheck(String pkg){
        PackageManager pm=getPackageManager();
        ApplicationInfo ai;
        try{ ai=pm.getApplicationInfo(pkg,0); }
        catch(Throwable t){ setStatus(false,"Пакет не найден: "+pkg); return; }
        SoSource s = locate(ai);
        if(s==null){ setStatus(false,"В пакете "+pkg+" не найден libunity.so (arm64-v8a).\n"
            +"Либо это не Unity-игра, либо другая ABI."); return; }
        StringBuilder sb=new StringBuilder();
        try{
            byte[] bid = s.read(OFF_BUILDID,8);
            byte[] cw  = s.read(OFF_CODE,4);
            byte[] r1  = s.read(OFF_RADAR, EXPECT_RADAR.length());
            byte[] r2  = s.read(OFF_RADAR2, EXPECT_RADAR2.length());

            String gotBid = bid==null?"(нет)":hex(bid);
            boolean okBid = gotBid.equalsIgnoreCase(EXPECT_BUILDID);
            int gotWord = cw==null?0:((cw[0]&0xff)|((cw[1]&0xff)<<8)|((cw[2]&0xff)<<16)|((cw[3]&0xff)<<24));
            boolean okCode = cw!=null && gotWord==EXPECT_CODE_WORD;
            String gotR1 = r1==null?"(нет)":new String(r1,"US-ASCII");
            boolean okR1 = EXPECT_RADAR.equals(gotR1);
            String gotR2 = r2==null?"(нет)":new String(r2,"US-ASCII");
            boolean okR2 = EXPECT_RADAR2.equals(gotR2);

            sb.append("Пакет: ").append(pkg).append("\n");
            sb.append("Источник: ").append(s.desc).append("\n\n");
            sb.append(line("Build-ID", okBid, EXPECT_BUILDID, gotBid));
            sb.append(line("Код @0x5ac7728", okCode,
                String.format("0x%08x",EXPECT_CODE_WORD), String.format("0x%08x",gotWord)));
            sb.append(line("Строка @46402888", okR1, EXPECT_RADAR, gotR1));
            sb.append(line("Строка @46404335", okR2, EXPECT_RADAR2, gotR2));

            boolean all = okBid && okCode && okR1 && okR2;
            if(all){
                sb.append("\nОффсеты и значения ПОДТВЕРЖДЕНЫ в установленной игре.");
                setStatus(true, sb.toString());
            } else if(!okBid){
                sb.append("\nBuild-ID не совпал → установлена ДРУГАЯ сборка движка, "
                        +"оффсеты от test.so к ней не относятся.");
                setStatus(false, sb.toString());
            } else {
                sb.append("\nBuild-ID совпал, но не все якоря сошлись (файл мог измениться).");
                setStatus(false, sb.toString());
            }
        }catch(Throwable t){
            setStatus(false,"Ошибка чтения libunity.so:\n"+t);
        } finally { s.close(); }
    }

    String line(String name, boolean ok, String exp, String got){
        String mark = ok?"✓":"✗";
        if(ok) return mark+" "+name+": "+got+"\n";
        return mark+" "+name+":\n    ждали: "+exp+"\n    факт : "+got+"\n";
    }

    // ---- источник байтов libunity.so: файл (extracted) или запись в APK ----
    static abstract class SoSource {
        String desc;
        abstract byte[] read(long off,int len) throws IOException;
        void close(){}
    }

    SoSource locate(ApplicationInfo ai){
        // 1) extracted native lib
        try{
            if(ai.nativeLibraryDir!=null){
                File f=new File(ai.nativeLibraryDir,"libunity.so");
                if(f.isFile() && f.canRead() && isArm64(ai.nativeLibraryDir))
                    return new FileSource(f);
            }
        }catch(Throwable ignored){}
        // 2) inside APK(s)
        List<String> apks=new ArrayList<String>();
        if(ai.sourceDir!=null) apks.add(ai.sourceDir);
        if(ai.splitSourceDirs!=null) for(String s: ai.splitSourceDirs) apks.add(s);
        for(String apk: apks){
            try{
                ZipFile zf=new ZipFile(apk);
                ZipEntry best=null;
                Enumeration<? extends ZipEntry> e=zf.entries();
                while(e.hasMoreElements()){
                    ZipEntry ze=e.nextElement();
                    String n=ze.getName();
                    if(n.endsWith("/libunity.so") && n.contains("arm64")){ best=ze; break; }
                }
                if(best!=null) return new ZipSource(zf,best,apk);
                zf.close();
            }catch(Throwable ignored){}
        }
        return null;
    }
    boolean isArm64(String dir){ return dir!=null && (dir.contains("arm64")||dir.contains("aarch64")||!dir.contains("arm")); }

    static class FileSource extends SoSource {
        RandomAccessFile raf;
        FileSource(File f) throws IOException { raf=new RandomAccessFile(f,"r"); desc="extracted "+f.getPath(); }
        byte[] read(long off,int len) throws IOException {
            if(off+len>raf.length()) return null;
            raf.seek(off); byte[] b=new byte[len]; raf.readFully(b); return b;
        }
        void close(){ try{raf.close();}catch(Exception e){} }
    }

    // читает окна из (возможно сжатой) записи APK одним потоковым проходом по требованию
    static class ZipSource extends SoSource {
        ZipFile zf; ZipEntry ze;
        ZipSource(ZipFile z,ZipEntry e,String apk){ zf=z; ze=e; desc=apk+" › "+e.getName(); }
        byte[] read(long off,int len) throws IOException {
            InputStream in=zf.getInputStream(ze);
            try{
                long skipped=0;
                while(skipped<off){ long s=in.skip(off-skipped);
                    if(s<=0){ int c=in.read(); if(c<0) return null; skipped++; } else skipped+=s; }
                byte[] b=new byte[len]; int got=0;
                while(got<len){ int r=in.read(b,got,len-got); if(r<0) return null; got+=r; }
                return b;
            } finally { in.close(); }
        }
        void close(){ try{zf.close();}catch(Exception e){} }
    }

    static String hex(byte[] b){ StringBuilder s=new StringBuilder();
        for(byte x:b) s.append(String.format("%02x",x&0xff)); return s.toString(); }
}
