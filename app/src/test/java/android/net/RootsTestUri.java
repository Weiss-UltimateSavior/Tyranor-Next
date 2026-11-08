package android.net;

import android.os.Parcel;
import java.util.List;

/** Supplies only a root string to removeRoot; this fixture does not test Android URI parsing. */
public final class RootsTestUri extends Uri {
    private final String value;

    public RootsTestUri(String value) { this.value = value; }
    @Override public String toString() { return value; }
    @Override public Builder buildUpon() { throw unused(); }
    @Override public String getAuthority() { throw unused(); }
    @Override public String getEncodedAuthority() { throw unused(); }
    @Override public String getEncodedFragment() { throw unused(); }
    @Override public String getEncodedPath() { throw unused(); }
    @Override public String getEncodedQuery() { throw unused(); }
    @Override public String getEncodedSchemeSpecificPart() { throw unused(); }
    @Override public String getEncodedUserInfo() { throw unused(); }
    @Override public String getFragment() { throw unused(); }
    @Override public String getHost() { throw unused(); }
    @Override public String getLastPathSegment() { throw unused(); }
    @Override public String getPath() { throw unused(); }
    @Override public List<String> getPathSegments() { throw unused(); }
    @Override public int getPort() { throw unused(); }
    @Override public String getQuery() { throw unused(); }
    @Override public String getScheme() { throw unused(); }
    @Override public String getSchemeSpecificPart() { throw unused(); }
    @Override public String getUserInfo() { throw unused(); }
    @Override public boolean isHierarchical() { throw unused(); }
    @Override public boolean isRelative() { throw unused(); }
    @Override public int describeContents() { throw unused(); }
    @Override public void writeToParcel(Parcel destination, int flags) { throw unused(); }

    private static UnsupportedOperationException unused() {
        return new UnsupportedOperationException("Only the root string is used by this fixture");
    }
}
