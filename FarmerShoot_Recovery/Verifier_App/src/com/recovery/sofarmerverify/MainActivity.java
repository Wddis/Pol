package com.recovery.sofarmerverify;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

public class MainActivity extends Activity implements View.OnClickListener {

    static final String EXPECT_BUILDID = "69815f2befe135c2";
    static final long OFF_BUILDID = 0x280L;
    // Якоря ищем ПО СОДЕРЖИМОМУ (в оригинале оффсеты иные, чем в test.so):
    static final String[] NEEDLES = {
        "UnityInitialize", "RadarView", "RadarMapView", "RadarConfiguration",
        "Balders.farmershoot.Game", "AimDispersionCurve"
    };
    static final int FIND=0, CHECK=1, EXTRACT=2;

    EditText pkgInput;
    TextView status, details;
    LinearLayout root;
    Button findBtn, checkBtn, extractBtn;
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

        tv("Farmer SO Verifier v2", 22, true, 0);
        tv("Находит якоря в libunity.so УСТАНОВЛЕННОЙ игры и показывает их НАСТОЯЩИЕ оффсеты. "
          +"Build-ID эталон: "+EXPECT_BUILDID+". Без root.", 14, false, pad);

        pkgInput = new EditText(this);
        pkgInput.setHint("имя пакета игры, напр. com.balders.farmershoot");
        pkgInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        root.addView(pkgInput);

        findBtn    = btn("Авто-поиск игры (по Build-ID)");
        checkBtn   = btn("Проверить оффсеты (найти в оригинале)");
        extractBtn = btn("Извлечь libil2cpp.so + global-metadata.dat");

        status = new TextView(this);
        status.setTextSize(24); status.setTypeface(null,Typeface.BOLD);
        status.setPadding(0,pad,0,pad/2);
        root.addView(status);

        details = new TextView(this);
        details.setTextSize(13); details.setTypeface(Typeface.MONOSPACE);
        details.setTextIsSelectable(true);
        root.addView(details);

        setContentView(sv);
    }

    TextView tv(String t,int sz,boolean bold,int padBottom){
        TextView x=new TextView(this); x.setText(t); x.setTextSize(sz);
        if(bold) x.setTypeface(null,Typeface.BOLD); x.setPadding(0,0,0,padBottom);
        root.addView(x); return x; }
    Button btn(String t){ Button b=new Button(this); b.setText(t); b.setOnClickListener(this);
        root.addView(b); return b; }

    public void onClick(View v){
        String p=pkgInput.getText().toString().trim();
        if(v==findBtn){ start(FIND,p); }
        else if(v==checkBtn){
            if(p.isEmpty()){ set(null,"Введите имя пакета или нажмите авто-поиск."); return; }
            start(CHECK,p);
        } else if(v==extractBtn){
            if(p.isEmpty()){ set(null,"Сначала укажите/найдите пакет игры."); return; }
            start(EXTRACT,p);
        }
    }
    void start(int mode,String pkg){ set(null,"Работаю… (до минуты)"); new Thread(new Task(this,mode,pkg)).start(); }

    // ---- ВСЕ вспомогательные классы static (нестатические внутренние ломают d8 8.2.2) ----
    static final class Task implements Runnable {
        final MainActivity a; final int mode; final String pkg;
        Task(MainActivity a,int m,String p){ this.a=a; mode=m; pkg=p; }
        public void run(){ try{
            if(mode==FIND) a.autoFind(); else if(mode==CHECK) a.runCheck(pkg); else a.extract(pkg);
        }catch(Throwable t){ a.set(Boolean.FALSE,"Сбой: "+t); } }
    }
    static final class StatusUpdate implements Runnable {
        final MainActivity a; final Boolean ok; final String msg;
        StatusUpdate(MainActivity a,Boolean ok,String msg){ this.a=a; this.ok=ok; this.msg=msg; }
        public void run(){
            if(ok==null){ a.status.setText(""); }
            else if(ok.booleanValue()){ a.status.setText("✓  Рабочий"); a.status.setTextColor(0xFF1B8A2F); }
            else { a.status.setText("✗  Не совпадает"); a.status.setTextColor(0xFFC0241C); }
            a.details.setText(msg);
        }
    }
    static final class SetPkg implements Runnable { final MainActivity a; final String p;
        SetPkg(MainActivity a,String p){ this.a=a; this.p=p; }
        public void run(){ a.pkgInput.setText(p); } }
    static final class ZipStream extends FilterInputStream {
        final ZipFile zf;
        ZipStream(ZipFile zf, InputStream in){ super(in); this.zf=zf; }
        public void close() throws IOException { super.close(); zf.close(); }
    }

    void set(Boolean ok,String msg){ runOnUiThread(new StatusUpdate(this,ok,msg)); }

    // ================= авто-поиск по Build-ID =================
    void autoFind(){
        PackageManager pm=getPackageManager();
        List<ApplicationInfo> apps;
        try{ apps=pm.getInstalledApplications(0); }
        catch(Throwable t){ set(null,"Нет доступа к списку приложений: "+t); return; }
        int unity=0; List<String> matches=new ArrayList<String>();
        for(ApplicationInfo ai: apps){
            Src s=locateSo(ai,"libunity.so"); if(s==null) continue; unity++;
            try{ byte[] bid=readAt(s,OFF_BUILDID,8);
                 if(bid!=null && hex(bid).equalsIgnoreCase(EXPECT_BUILDID)) matches.add(ai.packageName);
            }catch(Throwable ignore){}
        }
        if(matches.isEmpty()){
            set(null,"Unity-игр с libunity.so(arm64): "+unity
               +"\nС Build-ID "+EXPECT_BUILDID+": 0\nВведите пакет вручную и нажмите «Проверить».");
        } else {
            runOnUiThread(new SetPkg(this,matches.get(0)));
            runCheck(matches.get(0));
        }
    }

    // ================= проверка: найти якоря в оригинале =================
    void runCheck(String pkg){
        ApplicationInfo ai=info(pkg); if(ai==null){ set(false,"Пакет не найден: "+pkg); return; }
        Src so=locateSo(ai,"libunity.so");
        if(so==null){ set(false,"libunity.so(arm64) не найден в "+pkg); return; }
        StringBuilder sb=new StringBuilder();
        try{
            byte[] bid=readAt(so,OFF_BUILDID,8);
            String gotBid=bid==null?"(нет)":hex(bid);
            boolean okBid=gotBid.equalsIgnoreCase(EXPECT_BUILDID);
            sb.append("Пакет: ").append(pkg).append("\nИсточник: ").append(so.desc).append("\n\n");
            sb.append(okBid?"✓":"✗").append(" Build-ID: ").append(gotBid).append("\n\n");

            long[] found=scan(so, NEEDLES);
            sb.append("Якоря и их РЕАЛЬНЫЕ оффсеты в оригинале:\n");
            int hits=0;
            for(int i=0;i<NEEDLES.length;i++){
                if(found[i]>=0){ hits++; sb.append(String.format("  ✓ %-22s @ %d (0x%x)\n",NEEDLES[i],found[i],found[i])); }
                else sb.append(String.format("  ✗ %-22s не найден\n",NEEDLES[i]));
            }
            Src il2=locateSo(ai,"libil2cpp.so");
            Src meta=locateAsset(ai,"global-metadata.dat");
            sb.append("\nФайлы для восстановления:\n");
            sb.append(il2!=null ? "  ✓ libil2cpp.so ("+human(il2.size)+")\n" : "  ✗ libil2cpp.so не найден\n");
            sb.append(meta!=null? "  ✓ global-metadata.dat ("+human(meta.size)+")\n" : "  ✗ global-metadata.dat не найден\n");
            if(il2!=null||meta!=null) sb.append("  → жми «Извлечь …», чтобы достать их.\n");

            if(okBid && hits>=2){
                sb.append("\nОффсеты/значения ПОДЛИННЫ и присутствуют в установленной игре.\n");
                sb.append("(В твоём test.so они были по другим, смещённым адресам.)");
                set(Boolean.TRUE, sb.toString());
            } else if(!okBid){
                sb.append("\nBuild-ID не совпал → установлена другая сборка.");
                set(Boolean.FALSE, sb.toString());
            } else {
                sb.append("\nЯкоря не найдены — возможно, другая игра/версия.");
                set(Boolean.FALSE, sb.toString());
            }
        }catch(Throwable t){ set(Boolean.FALSE,"Ошибка: "+t); }
    }

    // ================= извлечение реальных файлов =================
    void extract(String pkg){
        ApplicationInfo ai=info(pkg); if(ai==null){ set(Boolean.FALSE,"Пакет не найден: "+pkg); return; }
        File outDir=getExternalFilesDir(null);
        StringBuilder sb=new StringBuilder("Извлечение в:\n"+outDir+"\n\n");
        boolean any=false;
        try{
            Src il2=locateSo(ai,"libil2cpp.so");
            if(il2!=null){ File o=new File(outDir,"libil2cpp.so"); long n=copy(il2,o); sb.append("✓ libil2cpp.so → "+human(n)+"\n"); any=true; }
            else sb.append("✗ libil2cpp.so отсутствует\n");
            Src meta=locateAsset(ai,"global-metadata.dat");
            if(meta!=null){ File o=new File(outDir,"global-metadata.dat"); long n=copy(meta,o); sb.append("✓ global-metadata.dat → "+human(n)+"\n"); any=true; }
            else sb.append("✗ global-metadata.dat отсутствует\n");
            sb.append("\nЗабрать через проводник/USB:\n"+outDir+"\nили: adb pull "+outDir+"/");
            set(any?Boolean.TRUE:Boolean.FALSE, sb.toString());
        }catch(Throwable t){ set(Boolean.FALSE, sb+"\nОшибка: "+t); }
    }

    // ================= helpers =================
    ApplicationInfo info(String pkg){ try{ return getPackageManager().getApplicationInfo(pkg,0);}catch(Throwable t){return null;} }

    static class Src { File file; String apk; ZipEntry ze; long size; String desc;
        InputStream open() throws IOException {
            if(file!=null) return new BufferedInputStream(new FileInputStream(file),1<<16);
            ZipFile zf=new ZipFile(apk);
            return new BufferedInputStream(new ZipStream(zf, zf.getInputStream(ze)),1<<16);
        }
    }

    Src locateSo(ApplicationInfo ai, String soname){
        try{ if(ai.nativeLibraryDir!=null){ File f=new File(ai.nativeLibraryDir,soname);
            if(f.isFile()&&f.canRead()){ Src s=new Src(); s.file=f; s.size=f.length(); s.desc="extracted "+f.getPath(); return s; } } }catch(Throwable ig){}
        for(String apk: apks(ai)){
            try{ ZipFile zf=new ZipFile(apk); ZipEntry best=null;
                Enumeration<? extends ZipEntry> e=zf.entries();
                while(e.hasMoreElements()){ ZipEntry ze=e.nextElement(); String n=ze.getName();
                    if(n.endsWith("/"+soname) && n.contains("arm64")){ best=ze; break; } }
                zf.close();
                if(best!=null){ Src s=new Src(); s.apk=apk; s.ze=best; s.size=best.getSize(); s.desc=apk+" › "+best.getName(); return s; }
            }catch(Throwable ig){}
        }
        return null;
    }
    Src locateAsset(ApplicationInfo ai, String suffix){
        for(String apk: apks(ai)){
            try{ ZipFile zf=new ZipFile(apk); ZipEntry best=null;
                Enumeration<? extends ZipEntry> e=zf.entries();
                while(e.hasMoreElements()){ ZipEntry ze=e.nextElement();
                    if(ze.getName().endsWith(suffix)){ best=ze; break; } }
                zf.close();
                if(best!=null){ Src s=new Src(); s.apk=apk; s.ze=best; s.size=best.getSize(); s.desc=apk+" › "+best.getName(); return s; }
            }catch(Throwable ig){}
        }
        return null;
    }
    List<String> apks(ApplicationInfo ai){ List<String> l=new ArrayList<String>();
        if(ai.sourceDir!=null) l.add(ai.sourceDir);
        if(ai.splitSourceDirs!=null) for(String s: ai.splitSourceDirs) l.add(s); return l; }

    byte[] readAt(Src s, long off, int len) throws IOException {
        InputStream in=s.open();
        try{ long sk=0; while(sk<off){ long k=in.skip(off-sk); if(k<=0){ if(in.read()<0) return null; sk++; } else sk+=k; }
            byte[] b=new byte[len]; int g=0; while(g<len){ int r=in.read(b,g,len-g); if(r<0) return null; g+=r; } return b;
        } finally { in.close(); }
    }

    // многопаттерный поиск за один потоковый проход; первый оффсет каждого needle или -1
    long[] scan(Src s, String[] needles) throws IOException {
        byte[][] pats=new byte[needles.length][]; int maxLen=1;
        for(int i=0;i<needles.length;i++){ pats[i]=needles[i].getBytes("US-ASCII"); if(pats[i].length>maxLen) maxLen=pats[i].length; }
        long[] res=new long[needles.length]; Arrays.fill(res,-1);
        InputStream in=s.open();
        try{
            int CH=1<<20; byte[] buf=new byte[CH+maxLen]; int carry=0; long base=0; int remaining=needles.length;
            while(remaining>0){
                int r=in.read(buf,carry,CH); if(r<0) break;
                int total=carry+r;
                for(int i=0;i<needles.length;i++){
                    if(res[i]>=0) continue;
                    int idx=indexOf(buf,total,pats[i]);
                    if(idx>=0){ res[i]=base - carry + idx; remaining--; }
                }
                int keep=Math.min(maxLen-1,total);
                System.arraycopy(buf,total-keep,buf,0,keep);
                base += r; carry=keep;
            }
        } finally { in.close(); }
        return res;
    }
    static int indexOf(byte[] a,int len,byte[] p){
        int m=p.length;
        for(int i=0;i<=len-m;i++){ int j=0; while(j<m && a[i+j]==p[j]) j++; if(j==m) return i; } return -1; }

    long copy(Src s, File out) throws IOException {
        InputStream in=s.open(); OutputStream o=new BufferedOutputStream(new FileOutputStream(out),1<<16);
        long tot=0; byte[] b=new byte[1<<16]; int r;
        try{ while((r=in.read(b))>0){ o.write(b,0,r); tot+=r; } } finally { in.close(); o.close(); }
        return tot;
    }

    static String hex(byte[] b){ StringBuilder s=new StringBuilder(); for(byte x:b) s.append(String.format("%02x",x&0xff)); return s.toString(); }
    static String human(long n){ if(n<0) return "?"; double v=n; String[] u={"B","KB","MB","GB"}; int i=0; while(v>=1024&&i<3){v/=1024;i++;} return String.format("%.1f %s",v,u[i]); }
}
