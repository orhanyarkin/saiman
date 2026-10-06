import { useAuth } from "@/components/auth/auth-context";
import { Button } from "@/components/ui/button";
import { useCapabilities } from "@/lib/use-capabilities";
import { isReplayMode } from "@/lib/mode";

/** Nav control: who is connected (with the role from `/me`), Disconnect, or Connect. */
export function AuthStatus() {
  const { state, openDialog, disconnect } = useAuth();
  const { me } = useCapabilities();
  if (isReplayMode) {
    return null;
  }
  if (!state.connected) {
    return (
      <Button type="button" size="sm" variant="outline" className="ml-auto" onClick={openDialog}>
        Connect
      </Button>
    );
  }
  const role = me === null ? "role unknown" : me.roles.includes("OPERATOR") ? "operator" : "reader";
  return (
    <span className="ml-auto flex items-center gap-2 text-sm">
      <span>
        Connected <span className="text-muted-foreground">({role})</span>
      </span>
      <Button type="button" size="sm" variant="outline" onClick={disconnect}>
        Disconnect
      </Button>
    </span>
  );
}
