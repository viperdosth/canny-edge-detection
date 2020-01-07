#ifndef defs_h_INCLUDED
#define defs_h_INCLUDED

#define VERBOSE 0
#define BOOSTBLURFACTOR 90.0
#define NOEDGE 255
#define POSSIBLE_EDGE 128
#define EDGE 0
#define KERNEL_DIM 3


// uncomment for count stimulus with known 
// pixel values 
#define COUNT_STIM

#ifdef COUNT_STIM
#define rows       5
#define cols       10
#else 
#define rows       240
#define cols       320
#endif
#define sigma      0.6
#define t_low      0.3
#define t_high     0.8
#define windowsize 21

typedef unsigned char Image[rows * cols];



#endif // defs_h_INCLUDED

