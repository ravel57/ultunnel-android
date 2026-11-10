package ru.ravel.ultunnel.bg;

import ru.ravel.ultunnel.bg.ParceledListSlice;

interface INeighborTableCallback {
    oneway void onNeighborTableUpdated(in ParceledListSlice entries);
}
