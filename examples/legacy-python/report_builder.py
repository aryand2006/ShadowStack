# Legacy Python 2 module — used as input for ShadowStack's PythonAdapter.
# Each construct below triggers exactly one of the modernization rules.

import urllib2


class ReportBuilder:
    """Builds a payload that survives Python 2 idioms."""

    def __init__(self, rows):
        self.rows = rows

    def render(self):
        # py.print_stmt_to_call
        print "Rendering", len(self.rows), "rows"

        # py.xrange_to_range
        for i in xrange(len(self.rows)):
            row = self.rows[i]
            # py.iter_methods_to_views
            for k, v in row.iteritems():
                # py.unicode_to_str
                print k, unicode(v)

        # py.ne_operator
        if len(self.rows) <> 0:
            return self._summarize()
        return None

    def _summarize(self):
        try:
            return urllib2.urlopen("https://example.invalid/api").read()
        # py.except_comma_to_as
        except Exception, e:
            print "summarize failed:", e
            return None
