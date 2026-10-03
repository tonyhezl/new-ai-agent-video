"use client";

import { useEffect, useSyncExternalStore } from "react";
import { useRouter } from "next/navigation";
import { getAuthorizedLandingPath } from "@/lib/auth/authorized-landing";
import { useAuthStore } from "@/lib/store/auth-store";
import { usePermissionStore } from "@/lib/store/permission-store";

export default function EntryPage() {
  const router = useRouter();
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated());
  const permissions = usePermissionStore((state) => state.permissions);
  const permissionsLoaded = usePermissionStore((state) => state.loaded);
  const permissionContextProjectId = usePermissionStore(
    (state) => state.contextProjectId
  );
  const fetchPermissions = usePermissionStore((state) => state.fetchPermissions);
  const authHydrated = useSyncExternalStore(
    (onStoreChange) => {
      const unsubStart = useAuthStore.persist.onHydrate(onStoreChange);
      const unsubFinish = useAuthStore.persist.onFinishHydration(onStoreChange);
      return () => {
        unsubStart();
        unsubFinish();
      };
    },
    () => useAuthStore.persist.hasHydrated(),
    () => false
  );

  useEffect(() => {
    if (!authHydrated) return;
    if (!isAuthenticated) {
      router.replace("/login");
      return;
    }
    if (!permissionsLoaded || permissionContextProjectId !== null) {
      void fetchPermissions();
    }
  }, [
    authHydrated,
    fetchPermissions,
    isAuthenticated,
    permissionContextProjectId,
    permissionsLoaded,
    router,
  ]);

  useEffect(() => {
    if (
      !authHydrated ||
      !isAuthenticated ||
      !permissionsLoaded ||
      permissionContextProjectId !== null
    ) {
      return;
    }
    router.replace(getAuthorizedLandingPath(permissions) ?? "/403");
  }, [
    authHydrated,
    isAuthenticated,
    permissionContextProjectId,
    permissions,
    permissionsLoaded,
    router,
  ]);

  return (
    <div className="flex min-h-screen items-center justify-center bg-background">
      <div
        className="h-6 w-6 animate-spin rounded-full border-2 border-muted border-t-primary"
        aria-label="正在进入可访问模块"
      />
    </div>
  );
}
