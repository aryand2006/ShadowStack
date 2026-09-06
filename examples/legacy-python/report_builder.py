# Legacy Python 2 / early-3 modernization corpus (detection-rich).
# Prefer the single-issue modules below for verify-gated demo patches.

import urllib2
import ConfigParser
import Queue

def render(rows):
    print "Rendering", len(rows), "rows"
    for i in xrange(len(rows)):
        row = rows[i]
        for k, v in row.iteritems():
            print k, unicode(v)
    if len(rows) <> 0:
        name = raw_input("name")
        if rows[0].has_key(name):
            return rows[0][name]
    return None
