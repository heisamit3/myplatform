// The welcome email, as plain text plus HTML. displayName is user input, so it is HTML-escaped.

export interface Email {
  to: string;
  subject: string;
  text: string;
  html: string;
}

export function welcomeEmail(input: { to: string; displayName: string; webUrl: string }): Email {
  const name = input.displayName.trim();
  return {
    to: input.to,
    subject: 'Welcome to MyPlatform',
    text: [
      `Hi ${name},`,
      '',
      'Your MyPlatform account is ready. Create an organization to invite your team:',
      input.webUrl,
      '',
      'If you did not sign up, you can ignore this email.',
    ].join('\n'),
    html: [
      `<p>Hi ${escapeHtml(name)},</p>`,
      '<p>Your MyPlatform account is ready. Create an organization to invite your team:</p>',
      `<p><a href="${escapeHtml(input.webUrl)}">${escapeHtml(input.webUrl)}</a></p>`,
      '<p>If you did not sign up, you can ignore this email.</p>',
    ].join('\n'),
  };
}

function escapeHtml(value: string): string {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}
