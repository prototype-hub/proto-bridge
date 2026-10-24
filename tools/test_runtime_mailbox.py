"""The disposable SMTP fixture decodes MIME and cannot relay mail."""

from email.message import EmailMessage
import json
import os
from pathlib import Path
import smtplib
import tempfile
import unittest

from runtime_mailbox import LocalMailbox


class RuntimeMailboxTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='runtime-mailbox-test-')
        self.addCleanup(self.temporary.cleanup)
        self.path = Path(self.temporary.name) / 'mailbox.json'
        self.secrets = set()
        self.mailbox = LocalMailbox(self.path, self.secrets)
        self.addCleanup(self.mailbox.close)

    def test_actual_smtp_accepts_only_local_fixture_addresses_and_decodes_mime(self):
        message = EmailMessage()
        message['From'] = 'identity@example.invalid'
        message['To'] = 'review-test@example.invalid'
        message['Subject'] = '邮箱验证'
        message.set_content('验证码：123456\n五分钟有效。', charset='utf-8', cte='base64')
        message.add_alternative('<p>123456</p>', subtype='html')
        with smtplib.SMTP('127.0.0.1', self.mailbox.port, local_hostname='localhost', timeout=5) as smtp:
            smtp.send_message(message)
            with self.assertRaises(smtplib.SMTPRecipientsRefused):
                smtp.sendmail('identity@example.invalid', ['someone@outside.invalid'], message.as_bytes())
        self.assertEqual([{'recipient': 'review-test@example.invalid', 'code': '123456'}],
                         json.loads(self.path.read_text()))
        self.assertEqual({'review-test@example.invalid', '123456'}, self.secrets)
        self.assertEqual(0o600, os.stat(self.path).st_mode & 0o777)

    def test_message_without_one_unambiguous_code_is_not_accepted(self):
        message = EmailMessage()
        message.set_content('123456 and 654321')
        self.assertFalse(self.mailbox.accept('test@example.invalid', message.as_bytes()))
        self.assertEqual([], json.loads(self.path.read_text()))


if __name__ == '__main__':
    unittest.main()
