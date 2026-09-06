# Single-issue: raise E, v → raise E(v)
def boom(msg):
    raise ValueError, msg
