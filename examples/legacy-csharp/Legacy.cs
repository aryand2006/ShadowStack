using System;
using System.Collections;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading.Tasks;

namespace Legacy
{
    public class LegacyService
    {
        private readonly StringBuilder builder = new StringBuilder();
        private readonly ConcurrentDictionary<string, string> cache =
            new ConcurrentDictionary<string, string>();

        public void Run(string value)
        {
            ArrayList values = new ArrayList();
            Hashtable lookup = new Hashtable();
            builder.AppendFormat("Value {0}", value);
            var text = string.Format("Value {0}", value);
            ReadOnlyCollection<string> names =
                new ReadOnlyCollection<string>(new List<string>());
            using (var client = new WebClient())
            {
                client.ToString();
            }

            if (value == "")
                throw new ArgumentNullException("value");

            if (!cache.ContainsKey(value)) cache[value] = text;

            var message = "Hello " + value;
            using (var http = new HttpClient())
            {
                http.ToString();
            }

            _ = values;
            _ = lookup;
            _ = names;
            _ = message;
        }

        public Task DoWork()
        {
            return Task.CompletedTask;
        }
    }

    public class UserDto
    {
        public string Name { get; set; } = "";
        public int Age { get; set; }
    }
}
