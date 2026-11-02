package cl.duoc.biblioteca.admin.dto;

/**
 * Los dos tipos de cola que se pueden crear.
 *
 * CLASSIC  la de siempre, en un nodo.
 * QUORUM   replicada en los nodos del cluster (Raft). Siempre durable, y la
 *          unica que cuenta reentregas (x-delivery-limit).
 */
public enum TipoCola {
    CLASSIC, QUORUM
}
