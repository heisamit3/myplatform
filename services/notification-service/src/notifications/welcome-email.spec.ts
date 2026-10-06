import { describe, expect, it } from 'vitest';
import { welcomeEmail } from './welcome-email.js';

describe('welcomeEmail', () => {
  const input = { to: 'a@example.com', displayName: 'Ada', webUrl: 'http://localhost:5173' };

  it('greets the user and links the web app', () => {
    const email = welcomeEmail(input);
    expect(email.to).toBe('a@example.com');
    expect(email.subject).toBe('Welcome to MyPlatform');
    expect(email.text).toContain('Hi Ada,');
    expect(email.text).toContain('http://localhost:5173');
    expect(email.html).toContain('<a href="http://localhost:5173">');
  });

  it('escapes the display name in HTML', () => {
    const email = welcomeEmail({ ...input, displayName: '<script>alert("x")</script> & co' });
    expect(email.html).toContain('Hi &lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt; &amp; co,');
    expect(email.html).not.toContain('<script>');
  });
});
