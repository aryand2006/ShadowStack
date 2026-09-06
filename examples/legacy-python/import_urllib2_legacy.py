# Single-issue: urllib2 → urllib.request
import urllib2

def fetch(url):
    return urllib2.urlopen(url)
