"""Loopback-only SMTP fixture for disposable runtime and browser verification.

Mail is never relayed. The private mailbox is removed with the verifier's
temporary workspace; neither its content nor OTP values belong in reports.
"""

from email import policy
from email.parser import BytesParser
import json
import os
from pathlib import Path
import re
import socketserver
import threading


class LocalMailbox:
    def __init__(self, path, secrets_seen):
        self.path = Path(path)
        self.secrets_seen = secrets_seen
        self.messages = []
        self.lock = threading.Lock()
        self._write()
        owner = self

        class Handler(socketserver.StreamRequestHandler):
            def handle(self):
                self.connection.settimeout(15)
                recipient = None

                def reply(value):
                    self.wfile.write(value + b'\r\n')
                    self.wfile.flush()

                reply(b'220 localhost disposable mailbox')
                while True:
                    line = self.rfile.readline(8193)
                    if not line or len(line) > 8192:
                        return
                    command = line.decode('ascii', 'replace').strip()
                    verb = command.split(' ', 1)[0].upper()
                    if verb in ('EHLO', 'HELO'):
                        reply(b'250 localhost')
                    elif verb == 'MAIL':
                        recipient = None
                        reply(b'250 OK')
                    elif verb == 'RCPT':
                        match = re.fullmatch(r'RCPT TO:\s*<([^<>\s]+@example\.invalid)>', command,
                                             re.IGNORECASE)
                        recipient = match.group(1).lower() if match else None
                        reply(b'250 OK' if recipient else b'550 local fixture recipients only')
                    elif verb == 'DATA' and recipient:
                        reply(b'354 End with a dot')
                        chunks, size = [], 0
                        while True:
                            chunk = self.rfile.readline(8193)
                            if not chunk or len(chunk) > 8192:
                                return
                            if chunk == b'.\r\n':
                                break
                            size += len(chunk)
                            if size > 65536:
                                return
                            chunks.append(chunk[1:] if chunk.startswith(b'..') else chunk)
                        if owner.accept(recipient, b''.join(chunks)):
                            reply(b'250 Stored locally')
                        else:
                            reply(b'550 Invalid verification message')
                    elif verb == 'QUIT':
                        reply(b'221 Bye')
                        return
                    elif verb in ('RSET', 'NOOP'):
                        reply(b'250 OK')
                    else:
                        reply(b'502 Unsupported')

        class Server(socketserver.ThreadingTCPServer):
            daemon_threads = True
            allow_reuse_address = False

        self.server = Server(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever,
                                       name='runtime-local-smtp', daemon=True)
        self.thread.start()

    @property
    def port(self):
        return self.server.server_address[1]

    def _write(self):
        temporary = self.path.with_suffix('.next')
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, 'w') as output:
            json.dump(self.messages, output)
        temporary.replace(self.path)

    def accept(self, recipient, content):
        message = BytesParser(policy=policy.default).parsebytes(content)
        text = '\n'.join(part.get_content() for part in message.walk()
                         if part.get_content_type() == 'text/plain')
        codes = re.findall(r'(?<![0-9])[0-9]{6}(?![0-9])', text)
        if len(set(codes)) != 1:
            return False
        with self.lock:
            if len(self.messages) >= 256:
                return False
            code = codes[0]
            self.secrets_seen.update((recipient, code))
            self.messages.append({'recipient': recipient, 'code': code})
            self._write()
        return True

    def latest(self, recipient):
        with self.lock:
            return next((dict(item) for item in reversed(self.messages)
                         if item['recipient'] == recipient.lower()), None)

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
        self.path.unlink(missing_ok=True)
