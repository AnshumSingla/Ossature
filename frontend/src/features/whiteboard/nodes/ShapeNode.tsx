import { useEffect, useRef, useState } from 'react';
import { Handle, NodeResizer, Position, type NodeProps } from 'reactflow';

export type ShapeKind = 'component' | 'datastore' | 'client';

export interface ShapeNodeData {
  label: string;
  shape: ShapeKind;
  locked: boolean;
  onRename: (label: string) => void;
}

const HANDLE_POSITIONS = [Position.Top, Position.Right, Position.Bottom, Position.Left];

function ShapeIcon({ shape }: { shape: ShapeKind }) {
  switch (shape) {
    case 'datastore':
      return (
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <ellipse cx="12" cy="6" rx="8" ry="3" />
          <path d="M4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6" />
          <path d="M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3" />
        </svg>
      );
    case 'client':
      return (
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <rect x="3" y="4" width="18" height="12" rx="1.5" />
          <path d="M8 20h8M12 16v4" />
        </svg>
      );
    default:
      return (
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <rect x="3" y="3" width="7" height="7" rx="1" />
          <rect x="14" y="3" width="7" height="7" rx="1" />
          <rect x="3" y="14" width="7" height="7" rx="1" />
          <rect x="14" y="14" width="7" height="7" rx="1" />
        </svg>
      );
  }
}

// A single handle per side does double duty as both source and target - paired with
// `connectionMode="loose"` on <ReactFlow> (see Whiteboard.tsx). Stacking a separate
// target+source Handle at each position (the "textbook" multi-handle pattern) looks right
// but breaks in practice: React Flow's hit-test (`elementsFromPoint`) finds whichever one
// paints on top, and in strict mode a same-position type mismatch silently fails the
// connection - confirmed by instrumenting React Flow's own connection validator mid-drag.
export function ShapeNode({ data, selected }: NodeProps<ShapeNodeData>) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(data.label);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => setDraft(data.label), [data.label]);
  useEffect(() => {
    if (editing) inputRef.current?.select();
  }, [editing]);

  const commit = () => {
    setEditing(false);
    if (draft.trim() && draft !== data.label) data.onRename(draft.trim());
    else setDraft(data.label);
  };

  return (
    <div className={`shape-node shape-${data.shape}`}>
      <NodeResizer isVisible={selected && !data.locked} minWidth={100} minHeight={48} />
      <div className="shape-node-body">
        <span className="shape-node-icon">
          <ShapeIcon shape={data.shape} />
        </span>
        {editing ? (
          <input
            ref={inputRef}
            className="shape-node-input"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onBlur={commit}
            onKeyDown={(e) => {
              if (e.key === 'Enter') commit();
              if (e.key === 'Escape') {
                setDraft(data.label);
                setEditing(false);
              }
            }}
            autoFocus
          />
        ) : (
          <span
            className="shape-node-label"
            onDoubleClick={() => !data.locked && setEditing(true)}
          >
            {data.label}
          </span>
        )}
      </div>
      {HANDLE_POSITIONS.map((pos) => (
        <Handle key={pos} type="source" position={pos} id={pos} />
      ))}
    </div>
  );
}
