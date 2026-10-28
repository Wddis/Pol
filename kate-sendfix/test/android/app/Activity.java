package android.app;
public class Activity extends android.content.Context {
    public static final int RESULT_OK = -1;
    public android.content.Intent intent; public boolean finished; public int result;
    public android.content.Intent getIntent() { return intent; }
    public boolean isFinishing() { return finished; }
    public void finish() { finished = true; }
    public void setResult(int r) { result = r; }
}
