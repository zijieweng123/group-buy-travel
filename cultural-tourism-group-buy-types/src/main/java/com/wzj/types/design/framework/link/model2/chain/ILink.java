package com.wzj.types.design.framework.link.model2.chain;

/**
 * @author wzj
 * @date 2026-01-21 17:08
 * @description 链接口
 */
public interface ILink<E> {

    boolean add(E e);

    boolean addFirst(E e);

    boolean addLast(E e);

    boolean remove(Object o);

    E get(int index);

    void printLinkList();

}

