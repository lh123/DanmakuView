package com.lh.danmakulibrary;

import java.util.Iterator;
import java.util.ListIterator;

/**
 * Created by liuhui on 2016/10/6.
 * DanmakuList
 */

@SuppressWarnings("WeakerAccess")
public class DanmakuList<T> implements Iterable<T> {

    private int size;
    private Node head;
    private Node end;
    private ListItr headListIterator;
    private ListItr endListIterator;

    public DanmakuList() {
        clear();
    }

    public void addToLast(T data) {
        Node node = new Node(data, end.prior, end);
        node.prior.next = node;
        end.prior = node;
        size++;
    }

    public void remove(Node node) {
        node.prior.next = node.next;
        node.next.prior = node.prior;
        size--;
    }

    public void clear() {
        head = new Node(null, null, null);
        end = new Node(null, head, null);
        head.next = end;
        size = 0;
    }

    @SuppressWarnings("unused")
    public int size() {
        return size;
    }

    @Override
    public Iterator<T> iterator() {
        return getHeadIterator();
    }

    public ListIterator<T> getHeadIterator() {
        if (headListIterator == null) {
            headListIterator = new ListItr(head.next);
        } else {
            headListIterator.setCurrentNode(head.next);
        }
        return headListIterator;
    }

    public ListIterator<T> getEndIterator() {
        if (endListIterator == null) {
            endListIterator = new ListItr(end.prior);
        } else {
            endListIterator.setCurrentNode(end.prior);
        }
        return endListIterator;
    }

    private class ListItr implements ListIterator<T> {

        private Node current;

        private ListItr(Node current) {
            this.current = current;
        }

        private void setCurrentNode(Node node) {
            current = node;
        }

        @Override
        public boolean hasNext() {
            return current != end;
        }

        @Override
        public T next() {
            T data = current.data;
            current = current.next;
            return data;
        }

        @Override
        public boolean hasPrevious() {
            return current != head;
        }

        @Override
        public T previous() {
            T data = current.data;
            current = current.prior;
            return data;
        }

        @Override
        public int nextIndex() {
            return 0;
        }

        @Override
        public int previousIndex() {
            return 0;
        }

        @Override
        public void remove() {
            DanmakuList.this.remove(current.prior);
        }

        @Override
        public void set(T t) {
            current.data = t;
        }

        @Override
        public void add(T t) {
            Node node = new Node(t, current, current.next);
            current.next = node;
            node.next.prior = node;
            size++;
        }
    }

    private class Node {
        public T data;
        private Node prior;
        private Node next;

        private Node(T data, Node prior, Node next) {
            this.data = data;
            this.prior = prior;
            this.next = next;
        }
    }
}
