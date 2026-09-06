using System.Collections;
using System.Collections.ObjectModel;
using System.Net;
using System.Runtime.Serialization.Formatters.Binary;

namespace Legacy;

public class LegacyService
{
    public void Run(string value)
    {
        ArrayList values = new ArrayList();
        Hashtable lookup = new Hashtable();
        builder.AppendFormat("Value {0}", value);
        var text = string.Format("Value {0}", value);
        ReadOnlyCollection<string> names = null;
        var client = new WebClient();
        var setting = ConfigurationManager.AppSettings["name"];
        var formatter = new BinaryFormatter();
        Thread.Abort();
        BeginInvoke(callback, null);

        if (value == "")
            throw new ArgumentNullException("value");

        if (!cache.ContainsKey(value)) cache[value] = text;
    }
}

using System.Runtime.Remoting;
using System.Runtime.Serialization.Formatters.Binary;
using System.Security.Permissions;

public class LegacySecurity {
  [PrincipalPermission(SecurityAction.Demand, Role = "Admin")]
  public void Dangerous() {
    var bf = new BinaryFormatter();
    Thread.CurrentThread.Abort();
  }
}

public class LegacyNet {
  public void Fetch(string url) {
    HttpWebRequest req = (HttpWebRequest)WebRequest.Create(url);
    NameValueCollection headers = new NameValueCollection();
  }
}
