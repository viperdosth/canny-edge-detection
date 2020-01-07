#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sim.sh>
#include <limits.h>
#include <math.h>

#include <c_typed_queue.sh>


#include "defs.sh"

// row based granularity 
typedef unsigned char tPixel;
typedef tPixel tRowPixel[cols];

typedef float tWeights[KERNEL_DIM][KERNEL_DIM];

#define CycleTime 10000000

// define row queue to get started
DEFINE_IC_TYPED_QUEUE(row, tRowPixel)
#define TPRINT(a) {sim_time_string buf; printf("Time =%5s: %s", time2str(buf, now()), a);};


// receive from queue and pass local data (for pipelined processing)
behavior QueueRx(i_row_receiver qIn, out tRowPixel rowOut) {
	void main(){
		qIn.receive(&rowOut);
	}
};

// receive pipeline and submit to queue
behavior QueueTx(in tRowPixel rowIn, i_row_sender qOut) {
	void main(){
		qOut.send(rowIn);
	}
};


behavior PipeBuffer(in tRowPixel rowIn, out tRowPixel rowOut){
	void main(void){
		rowOut = rowIn;
	}
};

behavior Convolution(in tRowPixel rowIn,in tRowPixel rowInL,in tRowPixel rowInLL, in tWeights kernel,out tRowPixel rowOut){
	int i;
    float result;
	int result1;
    void main(void) {
        for (i = 1; i < cols - 1; i++) {
            result = kernel[0][0] * rowInLL[i + 1] + kernel[0][1] * rowInLL[i] + kernel[0][2] * rowInLL[i - 1];
            result += (kernel[1][0] * rowInL[i + 1] + kernel[1][1] * rowInL[i] + kernel[1][2] * rowInL[i - 1]);
            result += (kernel[2][0] * rowIn[i + 1] + kernel[2][1] * rowIn[i] + kernel[2][2] * rowIn[i - 1]);
			result += 0.5;
			result1 = (int)result;
			result1 = (result1 > 255)? 255 : (result1 < 0)? 0: result1;
			
            rowOut[i] = (tPixel)result1;
        }
    }
};

// design under test 
behavior DUT(i_row_receiver qIn, i_row_sender qOut) {
	piped tRowPixel rowIn;
	piped tRowPixel rowInL;
	piped tRowPixel rowInLL;
	tRowPixel rowOut;
	QueueRx qRx(qIn,rowIn);
	QueueTx qTx(rowOut,qOut);
	PipeBuffer B1(rowIn,rowInL);
	PipeBuffer B2(rowInL,rowInLL);
    //tWeights weights = {{0, 0, 0}, {0, 1, 0}, {0, 0, 0}};
    //tWeights weights = {{0, 0, 1}, {0, 0, 0}, {0, 0, 0}};
    //tWeights weights = {{0, 0, 0}, {0, 0, 0}, {1, 0, 0}};
	tWeights weights = {{+1, 0, -1}, {+2, 0, -2}, {+1, 0, -1}};
	Convolution convolution(rowIn,rowInL,rowInLL,weights,rowOut);

	void main() {
		pipe{
			qRx;
			B1;
			B2;
			convolution;
			qTx;
			
		}
	}
};

// data input proxy 
behavior DataIn(i_row_receiver qIn, i_row_sender qOut) {
	tRowPixel i;

	void main() {
		while (true) {
			qIn.receive(&i);
			qOut.send(i);
			
		}
	}
};

// data output proxy
behavior DataOut(i_row_receiver qIn, i_row_sender qOut) {
	tRowPixel i;

	void main() {
		while (true) {
			qIn.receive(&i);
			qOut.send(i);
		}
	}
};

behavior Platform(i_row_receiver qIn, i_row_sender qOut) {
	c_row_queue inToCanny(2ul);
	c_row_queue outFromCanny(2ul);

	DataIn dIn(qIn, inToCanny);
	DUT canny(inToCanny, outFromCanny);
	DataOut dOut(outFromCanny, qOut);

	void main() {
		par {dIn; canny; dOut;}
	}
};



behavior Stimulus(i_row_sender q, in char *infilename) {
	unsigned char image[rows][cols];     /* The full input image */
	tRowPixel blank;
	int read_pgm_image();

	void main() {
		unsigned short rowNr; 
		/****************************************************************************
		* Read in the image. This read function allocates memory for the image.
		****************************************************************************/
		if (VERBOSE) printf("Reading the image %s.\n", infilename);
		// reads the complete image into behavior variable image 
		// TODO imporove read row by row
		if (read_pgm_image() == 0) {
			fprintf(stderr, "Error reading the input image, %s.\n", infilename);
			exit(1);
		}
		waitfor(CycleTime);
		// send out the data row by row 
		for (rowNr=0;rowNr<rows; rowNr++) {
			q.send(image[rowNr]);
		}
		while(1){
			q.send(blank);
		}
		
		
	}

	/******************************************************************************
	* Function: read_pgm_image
	* Purpose: This function reads in an image in PGM format. The image can be
	* read in from either a file or from standard input. The image is only read
	* from standard input when infilename = NULL. Because the PGM format includes
	* the number of columns and the number of rows in the image, these are read
	* from the file. Memory to store the image is allocated in this function.
	* All comments in the header are discarded in the process of reading the
	* image. Upon failure, this function returns 0, upon sucess it returns 1.
	******************************************************************************/
	int read_pgm_image() {
		FILE *fp;
		char buf[71];

		/***************************************************************************
		* Open the input image file for reading if a filename was given. If no
		* filename was provided, set fp to read from standard input.
		***************************************************************************/
		if (infilename == NULL) fp = stdin;
		else if ((fp = fopen(infilename, "r")) == NULL) {
			fprintf(stderr, "Error reading the file %s in read_pgm_image().\n",
			        infilename);
			return(0);
		}

		/***************************************************************************
		* Verify that the image is in PGM format, read in the number of columns
		* and rows in the image and scan past all of the header information.
		***************************************************************************/
		fgets(buf, 70, fp);
		if (strncmp(buf,"P5",2) != 0) {
			fprintf(stderr, "The file %s is not in PGM format in ", infilename);
			fprintf(stderr, "read_pgm_image().\n");
			if (fp != stdin) fclose(fp);
			return(0);
		}
		do {fgets(buf, 70, fp);} while (buf[0] == '#');  /* skip all comment lines */
		do {fgets(buf, 70, fp);} while (buf[0] == '#');  /* skip all comment lines */

		/***************************************************************************
		* Read the image from the file.
		***************************************************************************/
		if (rows != fread(image, cols, rows, fp)) {
			fprintf(stderr, "Error reading the image data in read_pgm_image().\n");
			if (fp != stdin) fclose(fp);
			return(0);
		}

		if (fp != stdin) fclose(fp);
		return(1);
	}
};



behavior Monitor(i_row_receiver q, in char *outfilename) {
	unsigned char edge[rows][cols];     /* The full output image but with dimensions*/

	int write_pgm_image(const char *comment, int maxval);

	void main() {
		unsigned short rowNr;
		
		// receive the data row by row 
		for (rowNr=0;rowNr<rows; rowNr++) {
			q.receive(&edge[rowNr]);
		}
		TPRINT("receive finish\n");
		
		/****************************************************************************
		* Write out the edge image to a file.
		****************************************************************************/

		if (write_pgm_image("Created by IrfanView", 255) == 0) {
			fprintf(stderr, "Error writing the edge image, %s.\n", outfilename);
			exit(1);
		}

		exit(0);
	}

	/******************************************************************************
	* Function: write_pgm_image
	* Purpose: This function writes an image in PGM format. The file is either
	* written to the file specified by outfilename or to standard output if
	* outfilename = NULL. A comment can be written to the header if coment != NULL.
	******************************************************************************/
	int write_pgm_image(const char *comment, int maxval) {
		FILE *fp;

		/***************************************************************************
		* Open the output image file for writing if a filename was given. If no
		* filename was provided, set fp to write to standard output.
		***************************************************************************/
		if(outfilename == NULL) fp = stdout;
		else if ((fp = fopen(outfilename, "w")) == NULL) {
			fprintf(stderr, "Error writing the file %s in write_pgm_image().\n",
			        outfilename);
			return(0);
		}

		/***************************************************************************
		* Write the header information to the PGM file.
		***************************************************************************/
		fprintf(fp, "P5\n");
		if (comment != NULL && strlen(comment) <= 70) fprintf(fp, "# %s\n", comment);
		fprintf(fp, "%d %d\n", cols, rows);
		fprintf(fp, "%d\n", maxval);

		/***************************************************************************
		* Write the image data to the file.
		***************************************************************************/
		if (rows != fwrite(edge, cols, rows, fp)) {
			fprintf(stderr, "Error writing the image data in write_pgm_image().\n");
			if (fp != stdout) fclose(fp);
			return(0);
		}

		if (fp != stdout) fclose(fp);
		return(1);
	}
};


// simple counting simtulus to have known dimentions 
behavior Stimulus_Count(i_row_sender q, in char *infilename) {

	tRowPixel outRow;
	tRowPixel blank;
	void main(void){
		unsigned int x,y,i;
		
		for (i = 0; i < cols; i++) {
            blank[i] = 0;
        }

		for (i = 0; i < KERNEL_DIM - 1; i++) {
            q.send(blank);
        }

		// generate pixels with the numbering scheme of
		// hexa decimal output first byte row, second byte column 
		// only works for images up to 16 cols wide
		for(y=0;y<rows;y++) {
			for(x=0;x<cols;x++) {
				outRow[x] = (unsigned char) y*16 +x;
			}
			q.send(outRow);
			waitfor(1);
		}
		while(1){
			q.send(blank);
		}
	}
};

behavior Monitor_Count(i_row_receiver q, in char *outfilename) {

	tRowPixel edge;
	void main() {
		unsigned short rowNr,x;
		
		// receive the data row by row and print on console 
		for (rowNr=0;rowNr<rows; rowNr++) {
			q.receive(&edge);
			printf("RX %d: ", rowNr);
			for (x=0;x<cols;x++) {
				printf("%02x ",edge[x]);
			}
			printf(" (%llu)\n",now());
		}
		
		exit(0);
	}
};



behavior Main {
	c_row_queue inToPlatform(2ul);
	c_row_queue outFromPlatform(2ul);

	char *infilename;
	char *outfilename;

	Platform plat(inToPlatform, outFromPlatform);
#ifdef COUNT_STIM
	Stimulus_Count stim(inToPlatform, infilename);
	Monitor_Count mon(outFromPlatform, outfilename);
#else
	Stimulus stim(inToPlatform, infilename);
	Monitor mon(outFromPlatform, outfilename);
#endif
	void remove_suffix(char *s, const char *suf);

	int main(int argc, char *argv[]) {
		char outfilename_buf[128];
		char filename[128];  /* Name of the input image without extension */
		char fNameDefault[] = "beachbus.pgm";

		/****************************************************************************
		* Get the command line arguments.
		****************************************************************************/
		if (argc < 2) {
			// if no file name given, assume default filenname
			infilename = fNameDefault;
		} else {
			infilename = argv[1];
		}

		outfilename = 0;

		if (strcmp(infilename, "-") == 0) {
			// use stdin
			infilename = 0;
			outfilename = 0;
		} else {
			// get the filename without pgm extension
			strncpy(filename, infilename, sizeof(filename));
			filename[sizeof(filename) - 1] = 0;
			remove_suffix(filename, ".pgm");
			snprintf(outfilename_buf, sizeof(outfilename_buf), "%s-edges.pgm", filename);
			outfilename = outfilename_buf;
		}
		
		par {stim; plat; mon;}

		return 0;
	}

	// remove suffix suf from string s, if it exists.
	// otherwise, don't modify s.
	// s and suf must be null terminated strings, *or else*.
	void remove_suffix(char *s, const char *suf) {
		size_t len_s;
		size_t len_suf;
		size_t offset;

		len_s = strlen(s);
		len_suf = strlen(suf);

		if (len_suf < len_s) {
			offset = len_s - len_suf;
			if (strcmp(s + offset, suf) == 0) s[offset] = 0;
		}
	}
};
