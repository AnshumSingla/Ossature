import { useCallback, useMemo, useRef } from 'react';
import ReactFlow, {
  Background,
  BackgroundVariant,
  ConnectionMode,
  Controls,
  MiniMap,
  ReactFlowProvider,
  SelectionMode,
  useReactFlow,
  type Edge,
  type Node,
} from 'reactflow';
import 'reactflow/dist/style.css';
import { useWhiteboardStore } from './whiteboardStore';
import { ShapeNode, type ShapeKind, type ShapeNodeData } from './nodes/ShapeNode';
import { LabeledEdge, type LabeledEdgeData } from './edges/LabeledEdge';

const nodeTypes = { shape: ShapeNode };
const edgeTypes = { labeled: LabeledEdge };

const PALETTE: { shape: ShapeKind; label: string }[] = [
  { shape: 'component', label: 'Component' },
  { shape: 'datastore', label: 'Datastore' },
  { shape: 'client', label: 'Client' },
];

function PaletteIcon({ shape }: { shape: ShapeKind }) {
  return <span className={`wb-palette-swatch shape-${shape}`} aria-hidden="true" />;
}

function WhiteboardInner() {
  const {
    nodes,
    edges,
    locked,
    onNodesChange,
    onEdgesChange,
    onConnect,
    addNode,
    renameNode,
    renameEdge,
    deleteSelected,
  } = useWhiteboardStore();
  const wrapperRef = useRef<HTMLDivElement>(null);
  const { project } = useReactFlow();

  const hasSelection = nodes.some((n) => n.selected) || edges.some((e) => e.selected);

  const displayNodes: Node<ShapeNodeData>[] = useMemo(
    () =>
      nodes.map((n) => ({
        ...n,
        data: {
          label: String((n.data as { label?: string }).label ?? ''),
          shape: (n.data as { shape?: ShapeKind }).shape ?? 'component',
          locked,
          onRename: (label: string) => renameNode(n.id, label),
        },
      })),
    [nodes, locked, renameNode],
  );

  const displayEdges: Edge<LabeledEdgeData>[] = useMemo(
    () =>
      edges.map((e) => ({
        ...e,
        type: 'labeled',
        data: { locked, onRename: (label: string) => renameEdge(e.id, label) },
      })),
    [edges, locked, renameEdge],
  );

  const onDragOver = useCallback(
    (e: React.DragEvent) => {
      if (locked) return;
      e.preventDefault();
      e.dataTransfer.dropEffect = 'move';
    },
    [locked],
  );

  const onDrop = useCallback(
    (e: React.DragEvent) => {
      e.preventDefault();
      if (locked) return;
      const shape = e.dataTransfer.getData('application/ossature-shape') as ShapeKind;
      if (!shape) return;
      const bounds = wrapperRef.current?.getBoundingClientRect();
      if (!bounds) return;
      const position = project({ x: e.clientX - bounds.left, y: e.clientY - bounds.top });
      addNode(shape, position);
    },
    [locked, project, addNode],
  );

  const onNodeDoubleClick = useCallback(() => {
    // Editing now happens inline inside ShapeNode (double-click the label itself);
    // kept as a no-op handler so double-clicking elsewhere on the node is harmless.
  }, []);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <div className="wb-toolbar">
        <div className="wb-palette">
          {PALETTE.map((p) => (
            <button
              key={p.shape}
              type="button"
              className="wb-palette-item"
              draggable={!locked}
              disabled={locked}
              onDragStart={(e) => e.dataTransfer.setData('application/ossature-shape', p.shape)}
              onClick={() => addNode(p.shape)}
              title={`Add ${p.label} (drag onto the canvas to place precisely)`}
            >
              <PaletteIcon shape={p.shape} />
              {p.label}
            </button>
          ))}
        </div>
        <div className="wb-toolbar-actions">
          {hasSelection && !locked ? (
            <button type="button" onClick={deleteSelected} className="btn-danger">
              Delete selected
            </button>
          ) : (
            !locked && (
              <span className="muted wb-hint">
                Drag to select · Shift-click to add · right-click-drag to pan
              </span>
            )
          )}
          {locked && <span className="error-text">Diagram locked</span>}
        </div>
      </div>
      <div ref={wrapperRef} style={{ flex: 1 }} onDragOver={onDragOver} onDrop={onDrop}>
        <ReactFlow
          nodes={displayNodes}
          edges={displayEdges}
          nodeTypes={nodeTypes}
          edgeTypes={edgeTypes}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onConnect={onConnect}
          onNodeDoubleClick={onNodeDoubleClick}
          nodesDraggable={!locked}
          nodesConnectable={!locked}
          edgesFocusable={!locked}
          elementsSelectable={!locked}
          connectionMode={ConnectionMode.Loose}
          deleteKeyCode={['Backspace', 'Delete']}
          // Shift-click adds to the current selection; dragging on empty canvas draws a
          // marquee box (SelectionMode.Partial so a shape only needs to be touched, not
          // fully enclosed - makes it easy to rope in most of a diagram in one drag instead
          // of shift-clicking every node individually). Panning moves to right-click-drag
          // since left-drag is now the selection box.
          multiSelectionKeyCode="Shift"
          selectionOnDrag={!locked}
          selectionMode={SelectionMode.Partial}
          panOnDrag={[2]}
          snapToGrid
          snapGrid={[16, 16]}
          defaultEdgeOptions={{ type: 'labeled' }}
          fitView
        >
          <Background variant={BackgroundVariant.Dots} gap={16} size={1} />
          <Controls showInteractive={false} />
          <MiniMap
            pannable
            zoomable
            style={{ backgroundColor: 'var(--bg-sunken)' }}
            nodeColor="var(--border)"
          />
        </ReactFlow>
      </div>
    </div>
  );
}

export function Whiteboard() {
  return (
    <ReactFlowProvider>
      <WhiteboardInner />
    </ReactFlowProvider>
  );
}
