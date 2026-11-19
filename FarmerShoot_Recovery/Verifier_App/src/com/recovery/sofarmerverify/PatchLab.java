package com.recovery.sofarmerverify;

/** Нативная демо-либа: значение g_val по фиксированному оффсету в libprobe.so.
 *  Патч делается в СВОЁМ процессе (своя память RW) — root не нужен. */
public class PatchLab {
    public static boolean loaded = false;
    static {
        try { System.loadLibrary("probe"); loaded = true; }
        catch (Throwable t) { loaded = false; }
    }
    public static final long VADDR_OFFSET = 0x35b0L; // оффсет g_val внутри libprobe.so
    public static final int  ORIGINAL     = 0x11223344;

    public native int  probe();            // исполняет чтение g_val
    public native long valueAddr();        // адрес g_val в памяти процесса
    public native void patch(long addr, int v); // пишет 4 байта по адресу (своя память)
}
