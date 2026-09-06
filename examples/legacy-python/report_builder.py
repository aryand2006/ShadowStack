# Legacy Python 2 module — ShadowStack modernization demo.
# Triggers the full lib2to3 / modernize catalog shipped in PythonAdapter.

import urllib2
import ConfigParser
import Queue
import thread
import cPickle
import cStringIO
import __builtin__


class ReportBuilder:
    def __init__(self, rows):
        self.rows = rows

    def render(self):
        print "Rendering", len(self.rows), "rows"
        for i in xrange(len(self.rows)):
            row = self.rows[i]
            for k, v in row.iteritems():
                print k, unicode(v)
        if len(self.rows) <> 0:
            return self._summarize()
        return None

    def _summarize(self):
        try:
            name = raw_input("name")
            if self.rows[0].has_key(name):
                return self.rows[0][name]
            n = long(1)
            apply(str, (n,))
            data = file("seed.txt").read()
            execfile("hooks.py")
            msg = u"ok"
            ch = unichr(65)
            reload(sys)
            intern(name)
            it = iter(self.rows)
            nxt = it.next()
            label = `n`
            return urllib2.urlopen("https://example.invalid/api").read()
        except StandardError, e:
            raise ValueError, e
