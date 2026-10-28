package android.content;
import java.util.*;
public class Intent { public Map<String,String> extras = new HashMap<>(); public String getStringExtra(String k) { return extras.get(k); } }
