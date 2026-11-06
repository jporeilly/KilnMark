import { useEffect, useId, useRef, useState } from "react";
import { allows, flowsApi, type AccessRole } from "../api";
import { go, href } from "../router";

/** The Delete button's id, so the focus can go back to it when the deletion is cancelled. */
export const DELETE_BUTTON_ID = "fl-delete";

/** Delete on a flow's page: only for designers and admins, who may delete flows (ADR 0048). */
export function DeleteFlowButton({ role, onAsk }: { role: AccessRole; onAsk: () => void }) {
  if (!allows(role, "designer")) return null;
  return (
    <button type="button" id={DELETE_BUTTON_ID} onClick={onAsk}>
      Delete
    </button>
  );
}

/**
 * The confirmation before a flow is deleted, shown on the page as the warning before running unsaved
 * work is: it names the flow, says what is deleted and what stays (and that unsaved changes on the
 * page are lost), and starts on Cancel. The focus moving to Cancel announces it: it is a group
 * named by its text, not an alert, so it is read once. Once deleted, the page goes to the Flows
 * list, or to the Library for a sample. The server refuses while a run is queued or running, and
 * its message is shown.
 */
export function DeleteFlowConfirm({
  name,
  title,
  sample,
  unsaved,
  onCancel,
  onError,
}: {
  name: string;
  title: string;
  sample?: boolean;
  /** Whether the page holds changes not saved, which go with the flow. */
  unsaved?: boolean;
  onCancel: () => void;
  onError: (err: unknown) => void;
}) {
  const [busy, setBusy] = useState(false);
  const cancel = useRef<HTMLButtonElement>(null);
  const textId = useId();
  useEffect(() => cancel.current?.focus(), []);
  const named = title === name ? name : `${title} (${name})`;

  const remove = async () => {
    setBusy(true);
    try {
      await flowsApi.remove(name);
      go(sample ? href.library() : href.flows());
    } catch (err) {
      setBusy(false);
      onError(err);
    }
  };

  return (
    <div className="warning pl-confirm" role="group" aria-labelledby={textId}>
      <p id={textId}>
        Delete the flow {named}? Its versions, its runs with what each node did, and its schedules are deleted. The
        pipeline runs it started stay, with what they wrote and the lineage they recorded.
        {unsaved ? " Your changes on this page that are not saved are lost too." : ""} This cannot be undone.
      </p>
      <div className="buttons">
        <button type="button" className="danger" onClick={remove} disabled={busy}>
          Delete {title}
        </button>
        <button type="button" onClick={onCancel} ref={cancel} disabled={busy}>
          Cancel
        </button>
      </div>
    </div>
  );
}
