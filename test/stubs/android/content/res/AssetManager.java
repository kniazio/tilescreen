package android.content.res;
public class AssetManager {
    public java.io.InputStream open(String name) throws java.io.IOException { return new java.io.FileInputStream("assets/" + name); }
}
