/**
 * Notification deep links are written by the sending module (`Notifications.SendRequest.deepLink`) and are stored
 * with the notification, so the inbox still holds links written by older builds. This maps such a link onto a route
 * of this SPA; `null` means "no link to follow" and the item opens nothing.
 *
 * Rewrites applied, in order:
 *  - `/workflow/…` → `/core/workflow/…`: the workflow links were written without the module prefix (they 404'd).
 *  - `/core/workflow/tasks/<taskId>` → `/core/workflow/tasks`: there is no per-task page, the inbox lists the task.
 */
export function resolveDeepLink(deepLink: string | null | undefined): string | null {
  const link = deepLink?.trim();
  if (!link || !link.startsWith('/')) return null;
  const prefixed = link.startsWith('/workflow/') ? `/core${link}` : link;
  return prefixed.replace(/^\/core\/workflow\/tasks\/[^/]+$/, '/core/workflow/tasks');
}
