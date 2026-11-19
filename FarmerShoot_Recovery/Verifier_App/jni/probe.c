/* минимальная JNI-либа без NDK: значение в .data, патчится в своём процессе */
typedef int jint; typedef long long jlong; typedef void* JNIEnv; typedef void* jobject;
volatile unsigned int g_val = 0x11223344u;   /* эталонное значение по оффсету */

jint Java_com_recovery_sofarmerverify_PatchLab_probe(JNIEnv* e, jobject o){
    return (jint)g_val;
}
jlong Java_com_recovery_sofarmerverify_PatchLab_valueAddr(JNIEnv* e, jobject o){
    return (jlong)(unsigned long)&g_val;
}
/* патч 4 байт по абсолютному адресу (адрес = база либы + оффсет g_val) */
void Java_com_recovery_sofarmerverify_PatchLab_patch(JNIEnv* e, jobject o, jlong addr, jint v){
    *((volatile unsigned int*)(unsigned long)addr) = (unsigned int)v;
}
