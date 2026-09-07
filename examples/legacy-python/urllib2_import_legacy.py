# LibCST-parseable: urllib2 → urllib.request (AST import)
import urllib2


def fetch(url):
    return urllib2.urlopen(url)
