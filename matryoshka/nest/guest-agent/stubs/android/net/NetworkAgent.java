package android.net;
public abstract class NetworkAgent {
    public NetworkAgent(android.content.Context context, android.os.Looper looper, String logTag, NetworkCapabilities nc,
            LinkProperties lp, int score, NetworkAgentConfig config, NetworkProvider provider) {}
    public Network register() { throw new RuntimeException("stub"); }
    public void markConnected() { throw new RuntimeException("stub"); }
    public void onNetworkUnwanted() {}
    public final void sendLinkProperties(LinkProperties lp) { throw new RuntimeException("stub"); }
}
