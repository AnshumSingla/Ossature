import {
  addEdge,
  applyEdgeChanges,
  applyNodeChanges,
  type Connection,
  type Edge,
  type EdgeChange,
  type Node,
  type NodeChange,
} from 'reactflow';
import { create } from 'zustand';
import type { Diagram } from '../../types/protocol';
import type { ShapeKind } from './nodes/ShapeNode';

let idSeq = 1;
const nextId = () => `n${idSeq++}`;

interface WhiteboardState {
  nodes: Node[];
  edges: Edge[];
  locked: boolean;
  onNodesChange: (changes: NodeChange[]) => void;
  onEdgesChange: (changes: EdgeChange[]) => void;
  onConnect: (conn: Connection) => void;
  addNode: (shape: ShapeKind, position?: { x: number; y: number }) => void;
  renameNode: (id: string, label: string) => void;
  renameEdge: (id: string, label: string) => void;
  deleteSelected: () => void;
  setLocked: (locked: boolean) => void;
  toDiagram: () => Diagram;
  reset: () => void;
}

const SHAPE_LABEL: Record<ShapeKind, string> = {
  component: 'Component',
  datastore: 'Datastore',
  client: 'Client',
};

// FR1: structured shapes only (boxes, arrows, labels) - no freehand (Non-Goal v1).
export const useWhiteboardStore = create<WhiteboardState>((set, get) => ({
  nodes: [],
  edges: [],
  locked: false,

  onNodesChange: (changes) => {
    if (get().locked) return;
    set({ nodes: applyNodeChanges(changes, get().nodes) });
  },
  onEdgesChange: (changes) => {
    if (get().locked) return;
    set({ edges: applyEdgeChanges(changes, get().edges) });
  },
  onConnect: (conn) => {
    if (get().locked) return;
    set({
      edges: addEdge(
        { ...conn, type: 'labeled', label: '' },
        get().edges,
      ),
    });
  },

  addNode: (shape, position) => {
    if (get().locked) return;
    const id = nextId();
    set({
      nodes: [
        ...get().nodes,
        {
          id,
          position: position ?? { x: 80 + Math.random() * 240, y: 80 + Math.random() * 240 },
          data: { label: SHAPE_LABEL[shape], shape },
          type: 'shape',
        },
      ],
    });
  },

  renameNode: (id, label) =>
    set({
      nodes: get().nodes.map((n) => (n.id === id ? { ...n, data: { ...n.data, label } } : n)),
    }),

  renameEdge: (id, label) =>
    set({
      edges: get().edges.map((e) => (e.id === id ? { ...e, label } : e)),
    }),

  deleteSelected: () => {
    if (get().locked) return;
    const remainingNodes = get().nodes.filter((n) => !n.selected);
    const remainingIds = new Set(remainingNodes.map((n) => n.id));
    const remainingEdges = get().edges.filter(
      (e) => !e.selected && remainingIds.has(e.source) && remainingIds.has(e.target),
    );
    set({ nodes: remainingNodes, edges: remainingEdges });
  },

  setLocked: (locked) => set({ locked }),

  toDiagram: () => ({
    nodes: get().nodes.map((n) => ({
      id: n.id,
      label: String((n.data as { label?: string }).label ?? ''),
      x: Math.round(n.position.x),
      y: Math.round(n.position.y),
      shape: (n.data as { shape?: ShapeKind }).shape,
    })),
    edges: get().edges.map((e) => ({
      id: e.id,
      source: e.source,
      target: e.target,
      label: typeof e.label === 'string' ? e.label : undefined,
    })),
  }),

  // Each live session should start from a clean board - the store is a module-level
  // singleton (shared across navigations in this SPA), so without this, opening session B
  // after session A would still show A's shapes.
  reset: () => set({ nodes: [], edges: [], locked: false }),
}));
