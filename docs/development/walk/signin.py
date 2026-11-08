#!/usr/bin/env python3
"""Signs in to Kilnmark through the bundled Keycloak, as a browser would, for the walk.

python3 signin.py KILNMARK_URL EMAIL < password   (the password on one line of standard input)

Follows Kilnmark's /api/v1/auth/login to Keycloak, signs in, sets up a one-time code when Keycloak
asks (or uses the one set up before, from ~/.km-walk-totp-<email>), signs in again with the code
when Kilnmark asks, and prints /api/v1/auth/status. Cookies are kept by host and port and sent over
plain HTTP to localhost, as browsers do for localhost. Prints no password and no secret.
"""
import base64, hashlib, hmac, html, json, os, re, struct, sys, time
import urllib.parse, urllib.request, urllib.error

base, email = sys.argv[1].rstrip('/'), sys.argv[2]
password = sys.stdin.readline().rstrip('\r\n')
jar = {}  # host:port -> {name: value}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


opener = urllib.request.build_opener(NoRedirect)


def where(url):
    p = urllib.parse.urlsplit(url)
    return '%s:%s' % (p.hostname, p.port)


def request(url, data=None):
    """One request, no redirects followed: (status, location, body)."""
    req = urllib.request.Request(url, data=urllib.parse.urlencode(data).encode() if data is not None else None)
    cookies = jar.get(where(url), {})
    if cookies:
        req.add_header('Cookie', '; '.join('%s=%s' % kv for kv in cookies.items()))
    try:
        resp = opener.open(req, timeout=60)
    except urllib.error.HTTPError as e:
        resp = e
    for h in resp.headers.get_all('Set-Cookie') or []:
        name, _, rest = h.partition('=')
        value = rest.split(';', 1)[0]
        gone = value == '' or re.search(r'(?i)max-age=0', h) or re.search(r'(?i)expires=[^;]*19[67]0', h)
        if gone:
            jar.get(where(url), {}).pop(name.strip(), None)
        else:
            jar.setdefault(where(url), {})[name.strip()] = value
    if os.environ.get('KM_TRACE'):
        print('  %s %s' % (resp.status, urllib.parse.urlsplit(url).path), file=sys.stderr)
    body = resp.read().decode('utf-8', 'replace')
    return resp.status, resp.headers.get('Location'), body


def follow(url, data=None, redirects=15):
    """Follows redirects from a request: (final url, status, body)."""
    status, loc, body = request(url, data)
    while loc and redirects > 0 and status in (301, 302, 303, 307):
        url = urllib.parse.urljoin(url, loc)
        status, loc, body = request(url)
        redirects -= 1
    return url, status, body


def form(body, ident=None):
    m = re.search(r'<form[^>]*id="%s"[^>]*action="([^"]+)"' % ident if ident else r'<form[^>]*action="([^"]+)"', body)
    return html.unescape(m.group(1)) if m else None


def code(secret):
    """A TOTP code as Keycloak makes it: HMAC-SHA1 over the raw secret, 30 s, 6 digits."""
    counter = int(time.time()) // 30
    digest = hmac.new(secret.encode(), struct.pack('>Q', counter), hashlib.sha1).digest()
    o = digest[-1] & 15
    return '%06d' % ((struct.unpack('>I', digest[o:o + 4])[0] & 0x7fffffff) % 1000000)


def fresh_code(secret, used):
    """A code from a later 30-second window than the one used last: Keycloak takes each once."""
    while True:
        c = code(secret)
        if c != used:
            return c
        time.sleep(2)


store = os.path.expanduser('~/.km-walk-totp-' + re.sub(r'[^a-z0-9]', '_', email))
secret = open(store).read().strip() if os.path.exists(store) else None
used = None


def sign_in(start):
    """Through Keycloak's forms from start: the password, then a code (set up when asked)."""
    global secret, used
    url, status, body = follow(start)
    action = form(body, 'kc-form-login')
    if not action:
        return url, status, body
    url, status, body = follow(action, {'username': email, 'password': password, 'credentialId': ''})
    if 'kc-totp-settings-form' in body or 'name="totpSecret"' in body:
        secret = html.unescape(re.search(r'name="totpSecret"[^>]*value="([^"]+)"', body).group(1))
        with open(os.open(store, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), 'w') as f:
            f.write(secret)
        used = fresh_code(secret, None)
        print('keycloak: set up a one-time code for', email)
        url, status, body = follow(form(body, 'kc-totp-settings-form') or form(body),
                                   {'totp': used, 'totpSecret': secret, 'userLabel': 'walk', 'mode': ''})
    elif 'kc-otp-login-form' in body or 'name="otp"' in body:
        used = fresh_code(secret, used)
        url, status, body = follow(form(body, 'kc-otp-login-form') or form(body), {'otp': used, 'login': 'Sign In'})
    if 'kc-form-login' in body or 'kc-feedback-text' in body:
        m = re.search(r'kc-feedback-text">([^<]*)<', body) or re.search(r'id="input-error[^"]*"[^>]*>([^<]*)<', body)
        sys.exit('keycloak refused the sign-in: %s' % (m.group(1).strip() if m else 'see its page'))
    return url, status, body


sign_in(base + '/api/v1/auth/login')
_, _, st = follow(base + '/api/v1/auth/status')
status = json.loads(st)
if status.get('signedIn') and status.get('refusal'):
    # Keycloak does not count setting a code up as using it: Kilnmark asks for a sign-in with it
    print('kilnmark: asks to sign in again, with the one-time code')
    sign_in(base + '/api/v1/auth/login?again=true')
    _, _, st = follow(base + '/api/v1/auth/status')
    status = json.loads(st)
print('kilnmark /api/v1/auth/status:', json.dumps(status, sort_keys=True))
sys.exit(0 if status.get('signedIn') and not status.get('refusal') else 1)
