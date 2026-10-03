export interface AuthorizedLandingRoute {
  path: string;
  permissionCode: string;
}

// Keep the order aligned with the primary navigation. Users land on the first
// module their role can actually access instead of assuming dashboard access.
export const AUTHORIZED_LANDING_ROUTES: readonly AuthorizedLandingRoute[] = [
  { path: "/dashboard", permissionCode: "dashboard:view" },
  { path: "/projects", permissionCode: "project:view" },
  { path: "/pov-stories", permissionCode: "pov:story:view" },
  { path: "/assets", permissionCode: "asset:view" },
  { path: "/media", permissionCode: "media:read" },
  { path: "/settings/users", permissionCode: "system:user:manage" },
  { path: "/settings/permissions", permissionCode: "system:role:manage" },
  { path: "/settings/ai-models", permissionCode: "ai:model:manage" },
  { path: "/settings/storage", permissionCode: "ai:config:manage" },
];

export function getAuthorizedLandingPath(
  permissions: ReadonlySet<string>
): string | null {
  if (permissions.has("*")) return "/dashboard";

  return (
    AUTHORIZED_LANDING_ROUTES.find(({ permissionCode }) =>
      permissions.has(permissionCode)
    )?.path ?? null
  );
}
